package com.ash.simpledataentry.di

import android.content.Context
import androidx.room.Room
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.DatabaseProvider
import com.ash.simpledataentry.data.D2Provider
import com.ash.simpledataentry.data.repositoryImpl.AuthRepositoryImpl
import com.ash.simpledataentry.data.repositoryImpl.DataEntryRepositoryImpl
import com.ash.simpledataentry.data.repositoryImpl.DatasetInstancesRepositoryImpl
import com.ash.simpledataentry.data.repositoryImpl.DatasetsRepositoryImpl
import com.ash.simpledataentry.data.repositoryImpl.SystemRepositoryImpl
import com.ash.simpledataentry.data.repositoryImpl.LoginUrlCacheRepository
import com.ash.simpledataentry.data.repositoryImpl.SavedAccountRepository
import com.ash.simpledataentry.data.repositoryImpl.ValidationRepository
import com.ash.simpledataentry.data.repositoryImpl.SettingsRepositoryImpl
import com.ash.simpledataentry.data.security.AccountEncryption
import com.ash.simpledataentry.domain.validation.ValidationService
import com.ash.simpledataentry.data.local.AppDatabase
import com.ash.simpledataentry.data.local.DataValueDraftDao
import com.ash.simpledataentry.data.local.DataElementDao
import com.ash.simpledataentry.data.local.CategoryComboDao
import com.ash.simpledataentry.data.local.CategoryOptionComboDao
import com.ash.simpledataentry.data.local.DatasetDao
import com.ash.simpledataentry.data.local.OrganisationUnitDao
import com.ash.simpledataentry.data.local.CachedUrlDao
import com.ash.simpledataentry.data.local.SavedAccountDao
import com.ash.simpledataentry.domain.repository.AuthRepository
import com.ash.simpledataentry.domain.repository.DataEntryRepository
import com.ash.simpledataentry.domain.repository.DatasetInstancesRepository
import com.ash.simpledataentry.domain.repository.DatasetsRepository
import com.ash.simpledataentry.domain.repository.SystemRepository
import com.ash.simpledataentry.domain.repository.SettingsRepository
import com.ash.simpledataentry.domain.useCase.DataEntryUseCases
import com.ash.simpledataentry.domain.useCase.FilterDatasetsUseCase
import com.ash.simpledataentry.domain.useCase.GetDataValuesUseCase
import com.ash.simpledataentry.domain.useCase.GetDatasetInstancesUseCase
import com.ash.simpledataentry.domain.useCase.GetDatasetsUseCase
import com.ash.simpledataentry.domain.useCase.LogoutUseCase
import com.ash.simpledataentry.domain.useCase.SaveDataValueUseCase
import com.ash.simpledataentry.domain.useCase.SyncDataEntryUseCase
import com.ash.simpledataentry.domain.useCase.SyncDatasetInstancesUseCase
import com.ash.simpledataentry.domain.useCase.SyncDatasetsUseCase
import com.ash.simpledataentry.domain.useCase.ValidateValueUseCase
import com.ash.simpledataentry.domain.useCase.CompleteDatasetInstanceUseCase
import com.ash.simpledataentry.domain.useCase.MarkDatasetInstanceIncompleteUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ash.simpledataentry.data.local.DataValueDao
import com.ash.simpledataentry.data.cache.MetadataCacheService
import com.ash.simpledataentry.data.cache.OrgUnitCacheService
import com.ash.simpledataentry.data.sync.BackgroundDataPrefetcher
import com.ash.simpledataentry.data.sync.NetworkStateManager
import com.ash.simpledataentry.data.sync.SyncQueueManager
import com.ash.simpledataentry.data.sync.SyncStatusController
import com.ash.simpledataentry.data.sync.BackgroundSyncManager

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE datasets ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE data_elements ADD COLUMN description TEXT")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS cached_urls (
                    url TEXT NOT NULL PRIMARY KEY,
                    lastUsed INTEGER NOT NULL,
                    frequency INTEGER NOT NULL DEFAULT 1,
                    isValid INTEGER NOT NULL DEFAULT 1
                )
            """)
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS saved_accounts (
                    id TEXT NOT NULL PRIMARY KEY,
                    displayName TEXT NOT NULL,
                    serverUrl TEXT NOT NULL,
                    username TEXT NOT NULL,
                    encryptedPassword TEXT NOT NULL,
                    lastUsed INTEGER NOT NULL,
                    isActive INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL
                )
            """)
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Create tracker_programs table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS tracker_programs (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT,
                    trackedEntityType TEXT,
                    categoryCombo TEXT,
                    styleIcon TEXT,
                    styleColor TEXT,
                    enrollmentDateLabel TEXT,
                    incidentDateLabel TEXT,
                    displayIncidentDate INTEGER NOT NULL DEFAULT 0,
                    onlyEnrollOnce INTEGER NOT NULL DEFAULT 0,
                    selectEnrollmentDatesInFuture INTEGER NOT NULL DEFAULT 0,
                    selectIncidentDatesInFuture INTEGER NOT NULL DEFAULT 0,
                    featureType TEXT NOT NULL DEFAULT 'NONE',
                    minAttributesRequiredToSearch INTEGER NOT NULL DEFAULT 1,
                    maxTeiCountToReturn INTEGER NOT NULL DEFAULT 50
                )
            """)

            // Create event_programs table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS event_programs (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT,
                    categoryCombo TEXT,
                    styleIcon TEXT,
                    styleColor TEXT,
                    featureType TEXT NOT NULL DEFAULT 'NONE'
                )
            """)
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Create tracker_enrollments table for caching enrollment instances
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS tracker_enrollments (
                    id TEXT NOT NULL PRIMARY KEY,
                    programId TEXT NOT NULL,
                    trackedEntityInstanceId TEXT NOT NULL,
                    organisationUnitId TEXT NOT NULL,
                    organisationUnitName TEXT NOT NULL,
                    enrollmentDate TEXT,
                    incidentDate TEXT,
                    status TEXT NOT NULL,
                    followUp INTEGER NOT NULL DEFAULT 0,
                    deleted INTEGER NOT NULL DEFAULT 0,
                    lastUpdated TEXT
                )
            """)

            // Create event_instances table for caching event instances
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS event_instances (
                    id TEXT NOT NULL PRIMARY KEY,
                    programId TEXT NOT NULL,
                    programStageId TEXT NOT NULL,
                    organisationUnitId TEXT NOT NULL,
                    organisationUnitName TEXT NOT NULL,
                    eventDate TEXT,
                    status TEXT NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0,
                    lastUpdated TEXT,
                    enrollmentId TEXT
                )
            """)
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_data_elements_categoryComboId ON data_elements(categoryComboId)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_category_option_combos_categoryComboId ON category_option_combos(categoryComboId)"
            )
        }
    }

    // Note: AccountManager, DatabaseManager, and SessionManager use @Inject constructors
    // and @Singleton annotations, so Hilt provides them automatically.
    // No manual @Provides methods needed.

    @Provides
    @Singleton
    fun provideAuthRepository(
        sessionManager: SessionManager,
        metadataCacheService: MetadataCacheService,
        backgroundSyncManager: BackgroundSyncManager
    ): AuthRepository {
        return AuthRepositoryImpl(sessionManager, metadataCacheService, backgroundSyncManager)
    }


    @Provides
    @Singleton
    fun provideSystemRepository(sessionManager: SessionManager): SystemRepository {

        return SystemRepositoryImpl(sessionManager)
    }

    @Provides
    @Singleton
    fun provideLogoutUseCase(authRepository: AuthRepository): LogoutUseCase {
        return LogoutUseCase(authRepository)
    }


    @Provides
    @Singleton
    fun provideDatasetsRepository(
        sessionManager: SessionManager,
        databaseProvider: DatabaseProvider,
        @ApplicationContext context: Context,
        datasetInstancesRepository: DatasetInstancesRepository,
        d2Provider: D2Provider
    ): DatasetsRepository {
        return DatasetsRepositoryImpl(sessionManager, databaseProvider, context, datasetInstancesRepository, d2Provider)
    }

    /**
     * UseCase for retrieving all datasets.
     */
    @Provides
    @Singleton
    fun provideGetDatasetsUseCase(repository: DatasetsRepository): GetDatasetsUseCase {
        return GetDatasetsUseCase(repository)
    }

    @Provides
    @Singleton
    fun provideSyncDatasetsUseCase(repository: DatasetsRepository): SyncDatasetsUseCase {
        return SyncDatasetsUseCase(repository)
    }

    @Provides
    @Singleton
    fun provideFilterDatasetsUseCase(repository: DatasetsRepository): FilterDatasetsUseCase {
        return FilterDatasetsUseCase(repository)
    }


    @Provides
    @Singleton
    fun provideDatasetInstancesRepository(
        sessionManager: SessionManager,
        databaseProvider: DatabaseProvider,
        d2Provider: D2Provider,
        orgUnitCache: OrgUnitCacheService
    ): DatasetInstancesRepository {
        return DatasetInstancesRepositoryImpl(sessionManager, databaseProvider, d2Provider, orgUnitCache)
    }



    @Provides
    @Singleton
    fun provideGetDatasetInstancesUseCase(
        repository: DatasetInstancesRepository
    ): GetDatasetInstancesUseCase {
        return GetDatasetInstancesUseCase(repository)
    }

    @Provides
    @Singleton
    fun provideSyncDatasetInstancesUseCase(
        repository: DatasetInstancesRepository
    ): SyncDatasetInstancesUseCase {
        return SyncDatasetInstancesUseCase(repository)
    }



    @Provides
    @Singleton
    fun provideMetadataCacheService(
        sessionManager: SessionManager,
        databaseProvider: DatabaseProvider,
        d2Provider: D2Provider
    ): MetadataCacheService {
        return MetadataCacheService(
            sessionManager,
            databaseProvider,
            d2Provider
        )
    }

    @Provides
    @Singleton
    fun provideBackgroundDataPrefetcher(
        sessionManager: SessionManager,
        metadataCacheService: MetadataCacheService,
        datasetDao: DatasetDao
    ): BackgroundDataPrefetcher {
        return BackgroundDataPrefetcher(
            sessionManager,
            metadataCacheService,
            datasetDao
        )
    }

    @Provides
    @Singleton
    fun provideNetworkStateManager(
        @ApplicationContext context: Context
    ): NetworkStateManager {
        return NetworkStateManager(context)
    }

    @Provides
    @Singleton
    fun provideSyncQueueManager(
        networkStateManager: NetworkStateManager,
        sessionManager: SessionManager,
        databaseProvider: DatabaseProvider,
        @ApplicationContext context: Context,
        d2Provider: D2Provider
    ): SyncQueueManager {
        return SyncQueueManager(networkStateManager, sessionManager, databaseProvider, context, d2Provider)
    }

    @Provides
    @Singleton
    fun provideSyncStatusController(
        syncQueueManager: SyncQueueManager
    ): SyncStatusController {
        return SyncStatusController(syncQueueManager)
    }

    @Provides
    @Singleton
    fun provideDataEntryRepository(
        sessionManager: SessionManager,
        databaseProvider: DatabaseProvider,
        metadataCacheService: MetadataCacheService,
        networkStateManager: NetworkStateManager,
        syncQueueManager: SyncQueueManager,
        d2Provider: D2Provider,
        orgUnitCache: OrgUnitCacheService,
        @ApplicationContext context: Context
    ): DataEntryRepository {
        return DataEntryRepositoryImpl(
            sessionManager,
            databaseProvider,
            context,
            metadataCacheService,
            networkStateManager,
            syncQueueManager,
            d2Provider,
            orgUnitCache
        )
    }

    @Provides
    @Singleton
    fun provideGetDataValuesUseCase(
        repository: DataEntryRepository
    ): GetDataValuesUseCase {
        return GetDataValuesUseCase(repository)
    }

    @Provides
    @Singleton
    fun provideSaveDataValueUseCase(
        repository: DataEntryRepository
    ): SaveDataValueUseCase {
        return SaveDataValueUseCase(repository)
    }

    @Provides
    @Singleton
    fun provideValidateValueUseCase(
        repository: DataEntryRepository
    ): ValidateValueUseCase {
        return ValidateValueUseCase(repository)
    }

    @Provides
    @Singleton
    fun provideCompleteDatasetInstanceUseCase(repository: DatasetInstancesRepository): CompleteDatasetInstanceUseCase {
        return CompleteDatasetInstanceUseCase(repository)
    }

    @Provides
    @Singleton
    fun provideMarkDatasetInstanceIncompleteUseCase(repository: DatasetInstancesRepository): MarkDatasetInstanceIncompleteUseCase {
        return MarkDatasetInstanceIncompleteUseCase(repository)
    }

    @Provides
    @Singleton
    fun provideDataEntryUseCases(
        dataEntryRepository: DataEntryRepository,
        datasetInstancesRepository: DatasetInstancesRepository
    ): DataEntryUseCases {
        return DataEntryUseCases(
            getDataValues = GetDataValuesUseCase(dataEntryRepository),
            saveDataValue = SaveDataValueUseCase(dataEntryRepository),
            validateValue = ValidateValueUseCase(dataEntryRepository),
            syncDataEntry = SyncDataEntryUseCase(dataEntryRepository),
            completeDatasetInstance = CompleteDatasetInstanceUseCase(datasetInstancesRepository),
            markDatasetInstanceIncomplete = MarkDatasetInstanceIncompleteUseCase(datasetInstancesRepository)
        )
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context
    ): SettingsRepository {
        return SettingsRepositoryImpl(context)
    }
    
    @Provides
    @Singleton
    fun provideBackgroundSyncManager(
        @ApplicationContext context: Context
    ): BackgroundSyncManager {
        return BackgroundSyncManager(context)
    }

    // Note: Global database removed - using DatabaseProvider for account-specific databases

    @Provides
    fun provideDataValueDraftDao(databaseProvider: DatabaseProvider): DataValueDraftDao {
        return databaseProvider.getCurrentDatabase().dataValueDraftDao()
    }

    @Provides
    fun provideDatasetDao(databaseProvider: DatabaseProvider): DatasetDao {
        return databaseProvider.getCurrentDatabase().datasetDao()
    }

    @Provides
    fun provideDataElementDao(databaseProvider: DatabaseProvider): DataElementDao {
        return databaseProvider.getCurrentDatabase().dataElementDao()
    }

    @Provides
    fun provideCategoryComboDao(databaseProvider: DatabaseProvider): CategoryComboDao {
        return databaseProvider.getCurrentDatabase().categoryComboDao()
    }

    @Provides
    fun provideCategoryOptionComboDao(databaseProvider: DatabaseProvider): CategoryOptionComboDao {
        return databaseProvider.getCurrentDatabase().categoryOptionComboDao()
    }

    @Provides
    fun provideOrganisationUnitDao(databaseProvider: DatabaseProvider): OrganisationUnitDao {
        return databaseProvider.getCurrentDatabase().organisationUnitDao()
    }

    @Provides
    fun provideDataValueDao(databaseProvider: DatabaseProvider): DataValueDao {
        return databaseProvider.getCurrentDatabase().dataValueDao()
    }

    @Provides
    fun provideCachedUrlDao(databaseProvider: DatabaseProvider): CachedUrlDao {
        return databaseProvider.getCurrentDatabase().cachedUrlDao()
    }

    @Provides
    fun provideTrackerProgramDao(databaseProvider: DatabaseProvider): com.ash.simpledataentry.data.local.TrackerProgramDao {
        return databaseProvider.getCurrentDatabase().trackerProgramDao()
    }

    @Provides
    fun provideEventProgramDao(databaseProvider: DatabaseProvider): com.ash.simpledataentry.data.local.EventProgramDao {
        return databaseProvider.getCurrentDatabase().eventProgramDao()
    }

    @Provides
    fun provideTrackerEnrollmentDao(databaseProvider: DatabaseProvider): com.ash.simpledataentry.data.local.TrackerEnrollmentDao {
        return databaseProvider.getCurrentDatabase().trackerEnrollmentDao()
    }

    @Provides
    fun provideEventInstanceDao(databaseProvider: DatabaseProvider): com.ash.simpledataentry.data.local.EventInstanceDao {
        return databaseProvider.getCurrentDatabase().eventInstanceDao()
    }

    @Provides
    fun provideSavedAccountDao(databaseProvider: DatabaseProvider): SavedAccountDao {
        return databaseProvider.getCurrentDatabase().savedAccountDao()
    }

    @Provides
    @Singleton
    fun provideAccountEncryption(): AccountEncryption {
        return AccountEncryption()
    }

    @Provides
    @Singleton
    fun provideLoginUrlCacheRepository(databaseProvider: DatabaseProvider): LoginUrlCacheRepository {
        return LoginUrlCacheRepository(databaseProvider)
    }

    @Provides
    @Singleton
    fun provideSavedAccountRepository(
        databaseProvider: DatabaseProvider,
        accountEncryption: AccountEncryption
    ): SavedAccountRepository {
        return SavedAccountRepository(databaseProvider, accountEncryption)
    }

    @Provides
    @Singleton
    fun provideValidationService(
        sessionManager: SessionManager
    ): ValidationService {
        return ValidationService(sessionManager)
    }

    @Provides
    @Singleton
    fun provideValidationRepository(
        validationService: ValidationService
    ): ValidationRepository {
        return ValidationRepository(validationService)
    }

}
