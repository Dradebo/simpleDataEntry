package com.ash.simpledataentry.data.cache

import android.util.Log
import com.ash.simpledataentry.data.D2Provider
import com.ash.simpledataentry.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.rx2.await
import org.hisp.dhis.android.core.organisationunit.OrganisationUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches scoped organization units to avoid repeated SDK queries.
 *
 * The same org unit scope query is executed 6+ times across different methods:
 * - DataEntryRepositoryImpl.getUserOrgUnits()
 * - DataEntryRepositoryImpl.getScopedOrgUnits()
 * - DataEntryRepositoryImpl.expandOrgUnitSelection()
 * - DatasetInstancesRepositoryImpl.getDatasetInstances()
 * - DatasetInstancesRepositoryImpl.getDatasetInstanceCount()
 * - etc.
 *
 * This service caches the result once per session, invalidating on account switch.
 */
@Singleton
class OrgUnitCacheService @Inject constructor(
    private val d2Provider: D2Provider,
    private val sessionManager: SessionManager
) {
    private val TAG = "OrgUnitCacheService"

    // Cached scoped org units (data capture scope)
    private var cachedScopedOrgUnits: List<OrganisationUnit>? = null
    private var cacheAccountId: String? = null

    init {
        // Invalidate cache on account switch
        sessionManager.currentAccountId
            .onEach { accountId ->
                if (accountId != cacheAccountId) {
                    Log.d(TAG, "Account changed from $cacheAccountId to $accountId - invalidating cache")
                    invalidateCache()
                    cacheAccountId = accountId
                }
            }
            .launchIn(CoroutineScope(Dispatchers.Default))
    }

    /**
     * Get all org units in data capture scope.
     * Returns cached result if available, otherwise fetches from SDK.
     */
    suspend fun getScopedOrgUnits(): List<OrganisationUnit> {
        cachedScopedOrgUnits?.let {
            Log.d(TAG, "Returning cached scoped org units (${it.size} units)")
            return it
        }

        Log.d(TAG, "Fetching scoped org units from SDK")
        val d2 = d2Provider.awaitD2()
        val orgUnits = d2.organisationUnitModule().organisationUnits()
            .byOrganisationUnitScope(OrganisationUnit.Scope.SCOPE_DATA_CAPTURE)
            .get().await()

        cachedScopedOrgUnits = orgUnits
        Log.d(TAG, "Cached ${orgUnits.size} scoped org units")
        return orgUnits
    }

    /**
     * Get scoped org unit UIDs as a Set for quick intersection operations.
     */
    suspend fun getScopedOrgUnitIds(): Set<String> {
        return getScopedOrgUnits().map { it.uid() }.toSet()
    }

    /**
     * Check if a specific org unit is in scope.
     */
    suspend fun isInScope(orgUnitId: String): Boolean {
        return getScopedOrgUnitIds().contains(orgUnitId)
    }

    /**
     * Invalidate the cache (e.g., on logout or account switch).
     */
    fun invalidateCache() {
        Log.d(TAG, "Cache invalidated")
        cachedScopedOrgUnits = null
    }

    /**
     * Prefetch and cache org units (call during login/session restore).
     */
    suspend fun prefetch() {
        Log.d(TAG, "Prefetching scoped org units")
        getScopedOrgUnits()
    }
}
