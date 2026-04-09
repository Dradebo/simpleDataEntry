package com.ash.simpledataentry.di

import android.content.Context
import androidx.room.Room
import com.ash.simpledataentry.data.SessionManager
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
import com.ash.simpledataentry.data.sync.AdaptiveSyncStrategy
import com.ash.simpledataentry.data.sync.BackgroundDataPrefetcher
import com.ash.simpledataentry.data.sync.NetworkStateManager
import com.ash.simpledataentry.data.sync.SyncQueueManager
import com.ash.simpledataentry.data.sync.BackgroundSyncManager
import com.ash.simpledataentry.data.sync.ConflictDetectionService
import com.ash.simpledataentry.data.sync.TransactionalSyncManager
import com.ash.simpledataentry.data.sync.EnhancedSyncOrchestrator
import com.ash.simpledataentry.data.sync.SyncService
import com.ash.simpledataentry.data.sync.ComprehensiveSyncOrchestrator
import com.ash.simpledataentry.data.session.EnhancedSessionManager

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


    @Provides
    @Singleton
    fun provideSessionManager(): SessionManager {
        return SessionManager()
    }

    @Provides
    @Singleton
    fun provideAuthRepository(sessionManager: SessionManager): AuthRepository {

        return AuthRepositoryImpl(sessionManager)

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
        datasetDao: DatasetDao,
        @ApplicationContext context: Context,
        datasetInstancesRepository: DatasetInstancesRepository
    ): DatasetsRepository {
        return DatasetsRepositoryImpl(sessionManager, datasetDao, context, datasetInstancesRepository)
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
        database: AppDatabase
    ): DatasetInstancesRepository {
        return DatasetInstancesRepositoryImpl(sessionManager, database)
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
        dataElementDao: DataElementDao,
        categoryComboDao: CategoryComboDao,
        categoryOptionComboDao: CategoryOptionComboDao,
        organisationUnitDao: OrganisationUnitDao,
        dataValueDao: DataValueDao
    ): MetadataCacheService {
        return MetadataCacheService(
            sessionManager,
            dataElementDao,
            categoryComboDao,
            categoryOptionComboDao,
            organisationUnitDao,
            dataValueDao
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
    fun provideAdaptiveSyncStrategy(): AdaptiveSyncStrategy {
        return AdaptiveSyncStrategy()
    }

    @Provides
    @Singleton
    fun provideSyncQueueManager(
        networkStateManager: NetworkStateManager,
        sessionManager: SessionManager,
        database: AppDatabase,
        adaptiveSyncStrategy: AdaptiveSyncStrategy
    ): SyncQueueManager {
        return SyncQueueManager(networkStateManager, sessionManager, database, adaptiveSyncStrategy)
    }

    @Provides
    @Singleton
    fun provideConflictDetectionService(
        sessionManager: SessionManager,
        database: AppDatabase
    ): ConflictDetectionService {
        return ConflictDetectionService(sessionManager, database)
    }

    @Provides
    @Singleton
    fun provideTransactionalSyncManager(
        sessionManager: SessionManager,
        database: AppDatabase,
        adaptiveSyncStrategy: AdaptiveSyncStrategy,
        networkStateManager: NetworkStateManager
    ): TransactionalSyncManager {
        return TransactionalSyncManager(sessionManager, database, adaptiveSyncStrategy, networkStateManager)
    }

    @Provides
    @Singleton
    fun provideEnhancedSyncOrchestrator(
        networkStateManager: NetworkStateManager,
        sessionManager: SessionManager,
        database: AppDatabase,
        conflictDetectionService: ConflictDetectionService,
        transactionalSyncManager: TransactionalSyncManager,
        adaptiveSyncStrategy: AdaptiveSyncStrategy
    ): EnhancedSyncOrchestrator {
        return EnhancedSyncOrchestrator(
            networkStateManager,
            sessionManager,
            database,
            conflictDetectionService,
            transactionalSyncManager,
            adaptiveSyncStrategy
        )
    }

    @Provides
    @Singleton
    fun provideSyncService(
        enhancedSyncOrchestrator: EnhancedSyncOrchestrator,
        syncQueueManager: SyncQueueManager
    ): SyncService {
        return SyncService(enhancedSyncOrchestrator, syncQueueManager)
    }

    @Provides
    @Singleton
    fun provideEnhancedSessionManager(
        baseSessionManager: SessionManager,
        accountEncryption: AccountEncryption
    ): EnhancedSessionManager {
        return EnhancedSessionManager(baseSessionManager, accountEncryption)
    }

    @Provides
    @Singleton
    fun provideComprehensiveSyncOrchestrator(
        sessionManager: SessionManager,
        enhancedSessionManager: EnhancedSessionManager,
        networkStateManager: NetworkStateManager,
        datasetsRepository: DatasetsRepository,
        datasetInstancesRepository: DatasetInstancesRepository,
        enhancedSyncOrchestrator: EnhancedSyncOrchestrator,
        database: AppDatabase
    ): ComprehensiveSyncOrchestrator {
        return ComprehensiveSyncOrchestrator(
            sessionManager,
            enhancedSessionManager,
            networkStateManager,
            datasetsRepository,
            datasetInstancesRepository,
            enhancedSyncOrchestrator,
            database
        )
    }

    @Provides
    @Singleton
    fun provideDataEntryRepository(
        sessionManager: SessionManager,
        draftDao: DataValueDraftDao,
        dataElementDao: DataElementDao,
        categoryComboDao: CategoryComboDao,
        categoryOptionComboDao: CategoryOptionComboDao,
        organisationUnitDao: OrganisationUnitDao,
        dataValueDao: DataValueDao,
        metadataCacheService: MetadataCacheService,
        networkStateManager: NetworkStateManager,
        syncQueueManager: SyncQueueManager,
        @ApplicationContext context: Context
    ): DataEntryRepository {
        return DataEntryRepositoryImpl(
            sessionManager,
            draftDao,
            dataElementDao,
            categoryComboDao,
            categoryOptionComboDao,
            organisationUnitDao,
            context,
            dataValueDao,
            metadataCacheService,
            networkStateManager,
            syncQueueManager
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

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "simple_data_entry_db"
        )
        .addMigrations(MIGRATION_1_2, MIGRATION_3_4, MIGRATION_4_5)
        .build()
    }

    @Provides
    fun provideDataValueDraftDao(db: AppDatabase): DataValueDraftDao = db.dataValueDraftDao()

    @Provides
    fun provideDatasetDao(db: AppDatabase): DatasetDao = db.datasetDao()

    @Provides
    fun provideDataElementDao(db: AppDatabase): DataElementDao = db.dataElementDao()

    @Provides
    fun provideCategoryComboDao(db: AppDatabase): CategoryComboDao = db.categoryComboDao()

    @Provides
    fun provideCategoryOptionComboDao(db: AppDatabase): CategoryOptionComboDao = db.categoryOptionComboDao()

    @Provides
    fun provideOrganisationUnitDao(db: AppDatabase): OrganisationUnitDao = db.organisationUnitDao()

    @Provides
    fun provideDataValueDao(db: AppDatabase): DataValueDao = db.dataValueDao()

    @Provides
    fun provideCachedUrlDao(db: AppDatabase): CachedUrlDao = db.cachedUrlDao()

    @Provides
    @Singleton
    fun provideLoginUrlCacheRepository(db: AppDatabase): LoginUrlCacheRepository {
        return LoginUrlCacheRepository(db)
    }

    @Provides
    fun provideSavedAccountDao(db: AppDatabase): SavedAccountDao = db.savedAccountDao()

    @Provides
    @Singleton
    fun provideAccountEncryption(): AccountEncryption {
        return AccountEncryption()
    }

    @Provides
    @Singleton
    fun provideSavedAccountRepository(
        db: AppDatabase,
        accountEncryption: AccountEncryption
    ): SavedAccountRepository {
        return SavedAccountRepository(db, accountEncryption)
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
