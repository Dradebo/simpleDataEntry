package com.ash.simpledataentry.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.Data
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.local.AppDatabase
import com.ash.simpledataentry.domain.repository.DatasetInstancesRepository
import com.ash.simpledataentry.domain.repository.DatasetsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker for syncing DHIS2 data
 * 
 * This worker:
 * - Syncs datasets metadata
 * - Syncs dataset instances and completion status  
 * - Uploads pending data values
 * - Runs based on user-configured sync frequency
 */
@HiltWorker
class BackgroundSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val sessionManager: SessionManager,
    private val datasetsRepository: DatasetsRepository,
    private val datasetInstancesRepository: DatasetInstancesRepository,
    private val networkStateManager: NetworkStateManager,
    private val syncQueueManager: SyncQueueManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "BackgroundSyncWorker"
        const val WORK_NAME = "background_sync_work"
    }

    private suspend fun updateProgress(step: String, progress: Int, error: String? = null) {
        val dataBuilder = Data.Builder()
            .putString("step", step)
            .putInt("progress", progress)
        error?.let { dataBuilder.putString("error", it) }
        setProgress(dataBuilder.build())
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting background sync worker...")

        if (!sessionManager.isSessionActive()) {
            Log.d(TAG, "No active session, skipping sync.")
            return@withContext Result.success()
        }

        if (!networkStateManager.isOnline()) {
            Log.d(TAG, "No network connection, will retry later.")
            return@withContext Result.retry()
        }

        try {
            // Step 1: Sync metadata (datasets and instances)
            updateProgress("Syncing metadata...", 10)
            datasetsRepository.syncDatasets().getOrThrow()
            updateProgress("Syncing instances...", 30)
            datasetInstancesRepository.syncDatasetInstances() // Assuming this doesn't return a result to check

            // Step 2: Upload pending data
            updateProgress("Uploading local data...", 60)
            val uploadResult = syncQueueManager.startSync(forceSync = true)

            return if (uploadResult.isSuccess) {
                updateProgress("Sync completed", 100)
                Log.d(TAG, "Background sync completed successfully.")
                Result.success()
            } else {
                val error = uploadResult.exceptionOrNull()?.message ?: "Unknown upload error"
                Log.e(TAG, "Background sync failed during data upload: $error")
                updateProgress("Sync failed", 0, error)
                Result.failure(workDataOf("error" to error))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Background sync failed with exception: ${e.message}", e)
            updateProgress("Sync failed", 0, e.message)
            return@withContext Result.failure(workDataOf("error" to e.message))
        }
    }
}