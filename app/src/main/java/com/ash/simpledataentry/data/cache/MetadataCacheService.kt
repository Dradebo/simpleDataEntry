package com.ash.simpledataentry.data.cache

import android.util.Log
import com.ash.simpledataentry.data.DatabaseProvider
import com.ash.simpledataentry.data.D2Provider
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.local.*
import com.ash.simpledataentry.domain.model.GroupingStrategy
import com.ash.simpledataentry.domain.model.DataValue
import com.ash.simpledataentry.domain.model.ValidationState
import com.ash.simpledataentry.domain.model.DataEntryType
import com.ash.simpledataentry.domain.model.ValidationRule
import com.ash.simpledataentry.domain.model.computeRenderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.hisp.dhis.android.core.D2
import org.hisp.dhis.android.core.common.ValueType
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for caching and optimizing metadata access across feature stacks.
 * Leverages data already loaded during login to reduce API calls in EditEntryScreen.
 *
 * CRITICAL: Uses DatabaseProvider for dynamic DAO access to ensure we always use
 * the correct account-specific database, not stale DAOs from app startup.
 */
@Singleton
class MetadataCacheService @Inject constructor(
    private val sessionManager: SessionManager,
    private val databaseProvider: DatabaseProvider,
    private val d2Provider: D2Provider
) {
    // Dynamic DAO accessors - always get from current database
    private val dataElementDao: DataElementDao get() = databaseProvider.getCurrentDatabase().dataElementDao()
    private val categoryComboDao: CategoryComboDao get() = databaseProvider.getCurrentDatabase().categoryComboDao()
    private val categoryOptionComboDao: CategoryOptionComboDao get() = databaseProvider.getCurrentDatabase().categoryOptionComboDao()
    private val organisationUnitDao: OrganisationUnitDao get() = databaseProvider.getCurrentDatabase().organisationUnitDao()
    private val dataValueDao: DataValueDao get() = databaseProvider.getCurrentDatabase().dataValueDao()

    /**
     * Use d2Provider.awaitD2() in suspend functions for safe, non-racing access.
     */
    private val d2: D2? get() = d2Provider.getD2OrNull()

    // Thread-safe caches using ConcurrentHashMap for concurrent access from async coroutines
    private val sectionsCache = ConcurrentHashMap<String, List<SectionInfo>>()
    private val categoryComboStructureCache = ConcurrentHashMap<String, List<Pair<String, List<Pair<String, String>>>>>()
    private val categoryOptionCombosCache = ConcurrentHashMap<String, List<Pair<String, List<String>>>>()
    private val groupingStrategyCache = ConcurrentHashMap<String, Map<String, List<GroupingStrategy>>>()
    private val impliedCategoryCache = ConcurrentHashMap<String, com.ash.simpledataentry.domain.model.ImpliedCategoryCombination>()

    // NEW: Cache for complete dataset form structures (metadata-only, no data values)
    // Key: datasetId, Value: DatasetFormStructure
    private val formStructureCache = ConcurrentHashMap<String, DatasetFormStructure>()

    // Mutex for protecting cache population to avoid duplicate SDK calls
    private val formStructureMutex = Mutex()

    data class SectionInfo(
        val name: String,
        val dataElementUids: List<String>
    )

    /**
     * Complete form structure derived purely from dataset metadata.
     * This is independent of any specific instance (period, orgUnit, etc.)
     * and can be cached indefinitely per dataset.
     */
    data class DatasetFormStructure(
        val datasetId: String,
        val sections: List<SectionInfo>,
        val dataElements: Map<String, DataElementEntity>,
        val categoryCombos: Map<String, CategoryComboEntity>,
        val categoryOptionCombos: Map<String, CategoryOptionComboEntity>,
        // Category combo UID -> List of (categoryName, List of (optionUid, optionName))
        val categoryStructures: Map<String, List<Pair<String, List<Pair<String, String>>>>>,
        // Category combo UID -> Map of (Set of option UIDs -> categoryOptionCombo UID)
        val optionUidsToComboUid: Map<String, Map<Set<String>, String>>,
        val optionSets: Map<String, com.ash.simpledataentry.domain.model.OptionSet>,
        val renderTypes: Map<String, com.ash.simpledataentry.domain.model.RenderType>
    )

    /**
     * Get the complete form structure for a dataset from metadata only.
     * This is the key method for decoupling metadata from data values.
     *
     * Key insight: Category structures are derived from data elements' categoryComboId,
     * NOT from iterating over data values. This means:
     * - New entries don't need any SDK calls (use cached structure)
     * - Existing entries use the same structure + load values separately
     *
     * This method is cached per dataset and can be called without instance parameters.
     */
    suspend fun getDatasetFormStructure(datasetId: String): DatasetFormStructure = withContext(Dispatchers.IO) {
        // Check cache first
        formStructureCache[datasetId]?.let { cached ->
            Log.d("MetadataCacheService", "Form structure cache HIT for dataset: $datasetId")
            return@withContext cached
        }

        // Use mutex to prevent duplicate SDK calls from concurrent coroutines
        formStructureMutex.withLock {
            // Double-check after acquiring lock
            formStructureCache[datasetId]?.let { cached ->
                Log.d("MetadataCacheService", "Form structure cache HIT (after lock) for dataset: $datasetId")
                return@withLock cached
            }

            Log.d("MetadataCacheService", "Form structure cache MISS for dataset: $datasetId - loading from metadata")

            val d2 = d2Provider.awaitD2()

            // 1. Get sections and data element UIDs (metadata structure)
            val sections = getSectionsForDatasetAsync(datasetId, d2)
            val allDataElementUids = sections.flatMap { it.dataElementUids }.distinct()

            Log.d("MetadataCacheService", "Loaded ${sections.size} sections with ${allDataElementUids.size} data elements")

            // 2. Get data elements from Room (already hydrated during login)
            val dataElements = if (allDataElementUids.isEmpty()) {
                emptyMap()
            } else {
                dataElementDao.getByIds(allDataElementUids).associateBy { it.id }
            }

            // 3. Get unique category combo IDs from data elements (KEY DIFFERENCE!)
            // This is derived from METADATA, not from data values
            val uniqueCategoryComboIds = dataElements.values
                .mapNotNull { it.categoryComboId }
                .filter { it.isNotBlank() }
                .distinct()

            Log.d("MetadataCacheService", "Found ${uniqueCategoryComboIds.size} unique category combos from data elements")

            // 4. Get category combos from Room
            val categoryCombos = if (uniqueCategoryComboIds.isEmpty()) {
                emptyMap()
            } else {
                categoryComboDao.getByIds(uniqueCategoryComboIds).associateBy { it.id }
            }

            // 5. Get ALL category option combos for these category combos
            val categoryOptionCombos = if (uniqueCategoryComboIds.isEmpty()) {
                emptyMap()
            } else {
                categoryOptionComboDao.getByCategoryComboIds(uniqueCategoryComboIds).associateBy { it.id }
            }

            // 6. Build category structures from category combos (not from data values!)
            val categoryStructures = mutableMapOf<String, List<Pair<String, List<Pair<String, String>>>>>()
            val optionUidsToComboUid = mutableMapOf<String, Map<Set<String>, String>>()

            for (categoryComboId in uniqueCategoryComboIds) {
                try {
                    val structure = getCategoryStructureForComboAsync(categoryComboId, d2)
                    categoryStructures[categoryComboId] = structure

                    val combos = getCategoryOptionCombosForComboAsync(categoryComboId, d2)
                    val mapping = combos.associate { coc ->
                        coc.second.toSet() to coc.first
                    }
                    optionUidsToComboUid[categoryComboId] = mapping

                    if (structure.isNotEmpty()) {
                        Log.d("MetadataCacheService", "  CategoryCombo $categoryComboId: ${structure.size} categories")
                    }
                } catch (e: Exception) {
                    Log.e("MetadataCacheService", "Error loading category structure for $categoryComboId", e)
                    categoryStructures[categoryComboId] = emptyList()
                    optionUidsToComboUid[categoryComboId] = emptyMap()
                }
            }

            // 7. Get option sets for data elements
            val optionSets = loadOptionSetsForDataset(datasetId, d2)

            // 8. Compute render types
            val renderTypes = optionSets.mapValues { (_, optionSet) ->
                optionSet.computeRenderType()
            }

            val formStructure = DatasetFormStructure(
                datasetId = datasetId,
                sections = sections,
                dataElements = dataElements,
                categoryCombos = categoryCombos,
                categoryOptionCombos = categoryOptionCombos,
                categoryStructures = categoryStructures,
                optionUidsToComboUid = optionUidsToComboUid,
                optionSets = optionSets,
                renderTypes = renderTypes
            )

            // Cache the result
            formStructureCache[datasetId] = formStructure
            Log.d("MetadataCacheService", "Form structure cached for dataset: $datasetId")

            formStructure
        }
    }

    /**
     * Generate empty DataValue shells for a new entry.
     * Uses the cached form structure to create placeholder values without any SDK calls.
     */
    fun generateEmptyDataValues(formStructure: DatasetFormStructure): List<DataValue> {
        val dataValues = mutableListOf<DataValue>()

        for (section in formStructure.sections) {
            for (dataElementUid in section.dataElementUids) {
                val dataElement = formStructure.dataElements[dataElementUid] ?: continue
                val categoryComboId = dataElement.categoryComboId ?: ""

                // Get category option combos for this data element's category combo
                val categoryOptionCombosForElement = formStructure.categoryOptionCombos.values
                    .filter { it.categoryComboId == categoryComboId }

                if (categoryOptionCombosForElement.isEmpty()) {
                    // No disaggregation - single default value
                    dataValues.add(
                        DataValue(
                            dataElement = dataElementUid,
                            dataElementName = dataElement.name,
                            sectionName = section.name,
                            categoryOptionCombo = "",
                            categoryOptionComboName = "Default",
                            value = null,
                            comment = null,
                            storedBy = null,
                            validationState = ValidationState.VALID,
                            dataEntryType = getDataEntryTypeFromValueType(dataElement.valueType),
                            lastModified = System.currentTimeMillis(),
                            validationRules = getValidationRulesFromValueType(dataElement.valueType)
                        )
                    )
                } else {
                    // Multiple disaggregations - one value per category option combo
                    for (coc in categoryOptionCombosForElement) {
                        dataValues.add(
                            DataValue(
                                dataElement = dataElementUid,
                                dataElementName = dataElement.name,
                                sectionName = section.name,
                                categoryOptionCombo = coc.id,
                                categoryOptionComboName = coc.name,
                                value = null,
                                comment = null,
                                storedBy = null,
                                validationState = ValidationState.VALID,
                                dataEntryType = getDataEntryTypeFromValueType(dataElement.valueType),
                                lastModified = System.currentTimeMillis(),
                                validationRules = getValidationRulesFromValueType(dataElement.valueType)
                            )
                        )
                    }
                }
            }
        }

        Log.d("MetadataCacheService", "Generated ${dataValues.size} empty data values for new entry")
        return dataValues
    }

    /**
     * Helper: Get sections using async SDK calls (non-blocking)
     */
    private suspend fun getSectionsForDatasetAsync(datasetId: String, d2: D2): List<SectionInfo> {
        // Check cache first
        sectionsCache[datasetId]?.let { return it }

        val sections = d2.dataSetModule().sections()
            .withDataElements()
            .byDataSetUid().eq(datasetId)
            .get().await()

        val result = if (sections.isEmpty()) {
            val dataSet = d2.dataSetModule().dataSets()
                .withDataSetElements()
                .uid(datasetId)
                .get().await()

            val dataElementUids = dataSet?.dataSetElements()
                ?.mapNotNull { it.dataElement()?.uid() }
                ?.distinct()
                .orEmpty()

            if (dataElementUids.isEmpty()) {
                emptyList()
            } else {
                listOf(SectionInfo(name = "Default Section", dataElementUids = dataElementUids))
            }
        } else {
            sections.map { section ->
                SectionInfo(
                    name = section.displayName() ?: "Unassigned",
                    dataElementUids = section.dataElements()?.map { it.uid() } ?: emptyList()
                )
            }
        }

        sectionsCache[datasetId] = result
        return result
    }

    /**
     * Helper: Get category structure for a category combo using async SDK calls
     * NOTE: This takes a CATEGORY COMBO ID, not a category option combo ID
     */
    private suspend fun getCategoryStructureForComboAsync(
        categoryComboId: String,
        d2: D2
    ): List<Pair<String, List<Pair<String, String>>>> {
        // Check cache first
        categoryComboStructureCache[categoryComboId]?.let { return it }

        try {
            val categoryCombo = d2.categoryModule().categoryCombos()
                .withCategories()
                .uid(categoryComboId)
                .get().await() ?: return emptyList()

            val categories = categoryCombo.categories() ?: emptyList()
            if (categories.isEmpty()) return emptyList()

            val result = categories.mapNotNull { catRef ->
                val category = d2.categoryModule().categories()
                    .withCategoryOptions()
                    .uid(catRef.uid())
                    .get().await() ?: return@mapNotNull null

                val options = category.categoryOptions() ?: emptyList()
                val optionPairs = options.map { optRef ->
                    optRef.uid() to (optRef.displayName() ?: optRef.uid())
                }

                (category.displayName() ?: category.uid()) to optionPairs
            }

            categoryComboStructureCache[categoryComboId] = result
            return result
        } catch (e: Exception) {
            Log.e("MetadataCacheService", "Error getting category structure for combo $categoryComboId", e)
            return emptyList()
        }
    }

    /**
     * Helper: Get category option combos for a category combo using async SDK calls
     */
    private suspend fun getCategoryOptionCombosForComboAsync(
        categoryComboId: String,
        d2: D2
    ): List<Pair<String, List<String>>> {
        // Check cache first
        categoryOptionCombosCache[categoryComboId]?.let { return it }

        try {
            val combos = d2.categoryModule().categoryOptionCombos()
                .byCategoryComboUid().eq(categoryComboId)
                .withCategoryOptions()
                .get().await()

            val result = combos.map { coc ->
                val optionUids = coc.categoryOptions()?.map { it.uid() } ?: emptyList()
                coc.uid() to optionUids
            }

            categoryOptionCombosCache[categoryComboId] = result
            return result
        } catch (e: Exception) {
            Log.e("MetadataCacheService", "Error getting category option combos for $categoryComboId", e)
            return emptyList()
        }
    }

    /**
     * Helper: Load option sets for a dataset
     */
    private suspend fun loadOptionSetsForDataset(
        datasetId: String,
        d2: D2
    ): Map<String, com.ash.simpledataentry.domain.model.OptionSet> {
        try {
            val sections = getSectionsForDatasetAsync(datasetId, d2)
            val dataElementUids = sections.flatMap { it.dataElementUids }.distinct()
            if (dataElementUids.isEmpty()) return emptyMap()

            val dataElements = d2.dataElementModule().dataElements()
                .byUid().`in`(dataElementUids)
                .withLegendSets()
                .get().await()

            val optionSetUids = dataElements.mapNotNull { it.optionSet()?.uid() }.distinct()
            if (optionSetUids.isEmpty()) return emptyMap()

            // Fetch option metadata (the option set objects themselves)
            val optionSetMetadata = d2.optionModule().optionSets()
                .byUid().`in`(optionSetUids)
                .get().await()
                .associateBy { it.uid() }

            // Fetch options grouped by option set
            val optionsBySet = d2.optionModule().options()
                .byOptionSetUid().`in`(optionSetUids)
                .get().await()
                .groupBy { it.optionSet()?.uid() ?: "" }

            val result = mutableMapOf<String, com.ash.simpledataentry.domain.model.OptionSet>()

            dataElements.forEach { de ->
                val optionSetUid = de.optionSet()?.uid() ?: return@forEach
                val options = optionsBySet[optionSetUid] ?: return@forEach
                val optionSetMeta = optionSetMetadata[optionSetUid]

                result[de.uid()] = com.ash.simpledataentry.domain.model.OptionSet(
                    id = optionSetUid,
                    name = optionSetMeta?.displayName() ?: optionSetMeta?.name() ?: optionSetUid,
                    options = options.map { opt ->
                        com.ash.simpledataentry.domain.model.Option(
                            code = opt.code() ?: opt.uid(),
                            name = opt.displayName() ?: opt.name() ?: opt.code() ?: opt.uid()
                        )
                    }
                )
            }

            return result
        } catch (e: Exception) {
            Log.e("MetadataCacheService", "Error loading option sets for dataset $datasetId", e)
            return emptyMap()
        }
    }

    /**
     * Helper: Convert value type string to DataEntryType
     */
    private fun getDataEntryTypeFromValueType(valueType: String?): DataEntryType {
        return when (valueType) {
            "TEXT", "LONG_TEXT" -> DataEntryType.TEXT
            "NUMBER" -> DataEntryType.NUMBER
            "INTEGER" -> DataEntryType.INTEGER
            "INTEGER_POSITIVE", "INTEGER_ZERO_OR_POSITIVE" -> DataEntryType.POSITIVE_INTEGER
            "INTEGER_NEGATIVE" -> DataEntryType.NEGATIVE_INTEGER
            "PERCENTAGE" -> DataEntryType.PERCENTAGE
            "DATE" -> DataEntryType.DATE
            "DATETIME" -> DataEntryType.DATETIME
            "TIME" -> DataEntryType.TIME
            "BOOLEAN" -> DataEntryType.YES_NO
            "TRUE_ONLY" -> DataEntryType.TRUE_ONLY
            "PHONE_NUMBER" -> DataEntryType.PHONE_NUMBER
            "COORDINATE" -> DataEntryType.COORDINATES
            else -> DataEntryType.TEXT
        }
    }

    /**
     * Helper: Get validation rules from value type
     */
    private fun getValidationRulesFromValueType(valueType: String?): List<ValidationRule> {
        val rules = mutableListOf<ValidationRule>()
        when (valueType) {
            "NUMBER", "INTEGER", "INTEGER_POSITIVE", "INTEGER_NEGATIVE",
            "INTEGER_ZERO_OR_POSITIVE", "PERCENTAGE" -> {
                rules.add(ValidationRule(
                    rule = "NUMERIC",
                    message = "Value must be a number",
                    severity = ValidationState.VALID
                ))
            }
        }
        return rules
    }

    /**
     * Invalidate form structure cache for a dataset
     */
    fun invalidateFormStructureCache(datasetId: String) {
        formStructureCache.remove(datasetId)
        Log.d("MetadataCacheService", "Form structure cache invalidated for dataset: $datasetId")
    }

    /**
     * Get optimized data for EditEntryScreen with pre-fetched and pre-mapped data values
     * @deprecated Use getDatasetFormStructure() for metadata and load values separately
     * This implements the requested architecture: metadata first, then data values mapped to UI structure
     */
    suspend fun getOptimizedDataForEntry(
        datasetId: String,
        period: String,
        orgUnit: String,
        attributeOptionCombo: String
    ): OptimizedEntryData = withContext(Dispatchers.IO) {

        Log.d("MetadataCacheService", "Getting optimized data for dataset: $datasetId")

        // 1. Get sections (cache or API) - this is the UI structure parsing
        val sections = getSectionsForDataset(datasetId)
        val allDataElementUids = sections.flatMap { it.dataElementUids }.distinct()

        // DEBUG: Log what UIDs we're looking for
        Log.d("MetadataCacheService", "Looking for ${allDataElementUids.size} data element UIDs from ${sections.size} sections")
        if (allDataElementUids.isNotEmpty()) {
            Log.d("MetadataCacheService", "First 5 UIDs requested: ${allDataElementUids.take(5)}")
        }

        // 2. Get data elements from Room (already hydrated during login)
        val totalInRoom = dataElementDao.count()
        val dataElements = dataElementDao.getByIds(allDataElementUids).associateBy { it.id }

        // DEBUG: Log what we found
        Log.d("MetadataCacheService", "Found ${dataElements.size} data elements in Room")
        if (dataElements.isEmpty() && allDataElementUids.isNotEmpty()) {
            // Check total data elements in Room
            val totalInRoom = dataElementDao.getAll().size
            Log.w("MetadataCacheService", "WARNING: Room has $totalInRoom total data elements but none match the requested UIDs!")
        }

        // 3. Get category combos from Room (already hydrated during login)
        val neededComboIds = dataElements.values.mapNotNull { it.categoryComboId }.distinct()
        val categoryCombos = if (neededComboIds.isEmpty()) {
            emptyMap()
        } else {
            categoryComboDao.getByIds(neededComboIds).associateBy { it.id }
        }

        // 4. Get category option combos from Room (already hydrated during login)
        val categoryOptionCombos = if (neededComboIds.isEmpty()) {
            emptyMap()
        } else {
            categoryOptionComboDao.getByCategoryComboIds(neededComboIds).associateBy { it.id }
        }

        // 5. Get org units from Room (already hydrated during login)
        val orgUnits = organisationUnitDao.getAll().associateBy { it.id }

        // 6. NEW STEP: Pre-fetch and map data values using the parsed UI structure
        val sdkDataValues = preFetchAndMapDataValues(datasetId, period, orgUnit, attributeOptionCombo)

        Log.d(
            "MetadataCacheService",
            "Optimized data complete: ${sections.size} sections, ${dataElements.size}/${totalInRoom} data elements, ${sdkDataValues.size} data values"
        )

        OptimizedEntryData(
            sections = sections,
            dataElements = dataElements,
            categoryCombos = categoryCombos,
            categoryOptionCombos = categoryOptionCombos,
            orgUnits = orgUnits,
            sdkDataValues = sdkDataValues
        )
    }
    
    /**
     * Get optimized data with fresh SDK data values for sync operations
     * Use this when you need the latest server data, not for regular data entry
     */
    suspend fun getOptimizedDataWithFreshValues(
        datasetId: String,
        period: String,
        orgUnit: String,
        attributeOptionCombo: String
    ): OptimizedEntryData = withContext(Dispatchers.IO) {
        
        Log.d("MetadataCacheService", "Getting optimized data with fresh values for dataset: $datasetId")
        
        // Get cached metadata (fast)
        val baseData = getOptimizedDataForEntry(datasetId, period, orgUnit, attributeOptionCombo)
        
        // Get fresh data values from SDK (slower, only when needed)
        val d2Instance = d2 ?: run {
            Log.w("MetadataCacheService", "D2 session not available for fresh values")
            return@withContext baseData
        }
        val sdkDataValues = d2Instance.dataValueModule().dataValues()
            .byDataSetUid(datasetId)
            .byPeriod().eq(period)
            .byOrganisationUnitUid().eq(orgUnit)
            .byAttributeOptionComboUid().eq(attributeOptionCombo)
            .blockingGet()
            .associateBy { (it.dataElement() ?: "") to (it.categoryOptionCombo() ?: "") }
        
        Log.d("MetadataCacheService", "Fresh data loaded: ${sdkDataValues.size} data values from server")
            
        baseData.copy(sdkDataValues = sdkDataValues)
    }
    
    /**
     * Get sections for a dataset with caching
     */
    private suspend fun getSectionsForDataset(datasetId: String): List<SectionInfo> {
        val d2Instance = d2 ?: run {
            Log.w("MetadataCacheService", "D2 session not available for getSectionsForDataset")
            return emptyList()
        }
        return sectionsCache.getOrPut(datasetId) {
            val sections = d2Instance.dataSetModule().sections()
                .withDataElements()
                .byDataSetUid().eq(datasetId)
                .blockingGet()

            if (sections.isEmpty()) {
                val dataSet = d2Instance.dataSetModule().dataSets()
                    .withDataSetElements()
                    .uid(datasetId)
                    .blockingGet()

                val dataElementUids = dataSet?.dataSetElements()
                    ?.mapNotNull { it.dataElement()?.uid() }
                    ?.distinct()
                    .orEmpty()

                if (dataElementUids.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        SectionInfo(
                            name = "Default Section",
                            dataElementUids = dataElementUids
                        )
                    )
                }
            } else {
                sections.map { section ->
                    SectionInfo(
                        name = section.displayName() ?: "Unassigned",
                        dataElementUids = section.dataElements()?.map { it.uid() } ?: emptyList()
                    )
                }
            }
        }
    }
    
    /**
     * Get category combo structure with caching
     * Categories and options are returned in the order provided by the SDK
     * (SDK maintains metadata-defined order internally)
     */
    suspend fun getCategoryComboStructure(categoryComboUid: String): List<Pair<String, List<Pair<String, String>>>> {
        val d2Instance = d2 ?: run {
            Log.w("MetadataCacheService", "D2 session not available for getCategoryComboStructure")
            return emptyList()
        }
        return categoryComboStructureCache.getOrPut(categoryComboUid) {
            try {
                // Get the category option combo to find its category combo
                val categoryOptionCombo = d2Instance.categoryModule().categoryOptionCombos()
                    .uid(categoryComboUid)
                    .blockingGet() ?: return@getOrPut emptyList()

                val actualCategoryComboUid = categoryOptionCombo.categoryCombo()
                    ?: return@getOrPut emptyList()

                // Get the category combo with its categories
                val categoryCombo = d2Instance.categoryModule().categoryCombos()
                    .withCategories()
                    .uid(actualCategoryComboUid.uid())
                    .blockingGet() ?: return@getOrPut emptyList()

                val categories = categoryCombo.categories() ?: emptyList()
                if (categories.isEmpty()) return@getOrPut emptyList()

                // Process categories in the order returned by SDK (maintains metadata order)
                // Use mapIndexed to preserve original order while processing
                categories.mapIndexedNotNull { catIndex, catRef ->
                    val category = d2Instance.categoryModule().categories()
                        .withCategoryOptions()
                        .uid(catRef.uid())
                        .blockingGet() ?: return@mapIndexedNotNull null

                    val options = category.categoryOptions() ?: emptyList()

                    // Options from SDK should already be in sorted order
                    val optionPairs = options.map { optRef ->
                        optRef.uid() to (optRef.displayName() ?: optRef.uid())
                    }

                    Log.d("MetadataCacheService", "Category $catIndex: ${category.displayName()} with ${optionPairs.size} options")

                    (category.displayName() ?: category.uid()) to optionPairs
                }
            } catch (e: Exception) {
                Log.e("MetadataCacheService", "Error getting category combo structure", e)
                emptyList()
            }
        }
    }
    
    /**
     * Get category option combos with caching
     */
    suspend fun getCategoryOptionCombos(categoryComboUid: String): List<Pair<String, List<String>>> {
        val d2Instance = d2 ?: run {
            Log.w("MetadataCacheService", "D2 session not available for getCategoryOptionCombos")
            return emptyList()
        }
        return categoryOptionCombosCache.getOrPut(categoryComboUid) {
            try {
                // Get the category option combo to find its category combo
                val categoryOptionCombo = d2Instance.categoryModule().categoryOptionCombos()
                    .uid(categoryComboUid)
                    .blockingGet() ?: return@getOrPut emptyList()

                val actualCategoryComboUid = categoryOptionCombo.categoryCombo()
                    ?: return@getOrPut emptyList()

                val combos = d2Instance.categoryModule().categoryOptionCombos()
                    .byCategoryComboUid().eq(actualCategoryComboUid.uid())
                    .withCategoryOptions()
                    .blockingGet()
                
                combos.map { coc ->
                    val optionUids = coc.categoryOptions()?.map { it.uid() } ?: emptyList()
                    coc.uid() to optionUids
                }
            } catch (e: Exception) {
                Log.e("MetadataCacheService", "Error getting category option combos", e)
                emptyList()
            }
        }
    }
    
    /**
     * Get cached grouping strategies for a dataset
     * Returns null if not cached - caller should compute and store
     */
    fun getGroupingStrategies(datasetId: String): Map<String, List<GroupingStrategy>>? {
        return groupingStrategyCache[datasetId]
    }

    /**
     * Store grouping strategies in cache
     * This persists across ViewModel lifecycle for performance
     */
    fun setGroupingStrategies(datasetId: String, strategies: Map<String, List<GroupingStrategy>>) {
        groupingStrategyCache[datasetId] = strategies
        Log.d("MetadataCacheService", "Cached grouping strategies for dataset $datasetId: ${strategies.size} sections")
    }

    /**
     * Get cached implied category combination for a program section
     * Returns null if not cached - caller should compute and store
     */
    fun getImpliedCategories(programId: String, sectionName: String): com.ash.simpledataentry.domain.model.ImpliedCategoryCombination? {
        val key = "$programId:$sectionName"
        return impliedCategoryCache[key]
    }

    /**
     * Store implied category combination in cache
     * This persists across ViewModel lifecycle for performance
     */
    fun setImpliedCategories(
        programId: String,
        sectionName: String,
        combination: com.ash.simpledataentry.domain.model.ImpliedCategoryCombination
    ) {
        val key = "$programId:$sectionName"
        impliedCategoryCache[key] = combination
        Log.d("MetadataCacheService", "Cached implied categories for $key: ${combination.categories.size} levels, confidence=${combination.confidence}")
    }

    /**
     * Clear all caches (useful when switching accounts or after sync)
     */
    fun clearAllCaches() {
        sectionsCache.clear()
        categoryComboStructureCache.clear()
        categoryOptionCombosCache.clear()
        groupingStrategyCache.clear()
        impliedCategoryCache.clear()
        formStructureCache.clear()
        Log.d("MetadataCacheService", "All metadata caches cleared")
    }
    
    /**
     * Pre-fetch and map data values using the parsed UI structure
     * This bridges the gap between metadata parsing and data retrieval
     */
    private suspend fun preFetchAndMapDataValues(
        datasetId: String,
        period: String,
        orgUnit: String,
        attributeOptionCombo: String
    ): Map<Pair<String, String>, org.hisp.dhis.android.core.datavalue.DataValue> {

        Log.d("MetadataCacheService", "Pre-fetching data values for dataset: $datasetId")

        // First check if we have cached data in Room
        val cachedDataValues = dataValueDao.getValuesForInstance(datasetId, period, orgUnit, attributeOptionCombo)

        if (cachedDataValues.isNotEmpty()) {
            Log.d("MetadataCacheService", "Using cached data values from Room: ${cachedDataValues.size} entries")

            // For cached data, we'll return empty map and let the DataEntryRepositoryImpl
            // handle retrieving data from Room cache directly - this avoids complex SDK mocking
            return emptyMap()
        }

        // If no cached data, fetch fresh data values from SDK
        val d2Instance = d2 ?: run {
            Log.w("MetadataCacheService", "D2 session not available for preFetchAndMapDataValues")
            return emptyMap()
        }
        val rawSdkDataValues = d2Instance.dataValueModule().dataValues()
            .byDataSetUid(datasetId)
            .byPeriod().eq(period)
            .byOrganisationUnitUid().eq(orgUnit)
            .byAttributeOptionComboUid().eq(attributeOptionCombo)
            .blockingGet()

        Log.d("MetadataCacheService", "Raw SDK data values retrieved: ${rawSdkDataValues.size}")

        // Map using the actual keys from SDK data - this fixes the key mismatch issue
        val mappedDataValues = rawSdkDataValues.associateBy {
            (it.dataElement() ?: "") to (it.categoryOptionCombo() ?: "")
        }

        // Store in Room for quick subsequent loading
        storeDataValuesInRoom(datasetId, period, orgUnit, attributeOptionCombo, rawSdkDataValues)

        Log.d("MetadataCacheService", "Data values mapped and cached: ${mappedDataValues.size} entries")

        return mappedDataValues
    }

    /**
     * Store SDK data values in Room database for quick subsequent access
     */
    private suspend fun storeDataValuesInRoom(
        datasetId: String,
        period: String,
        orgUnit: String,
        attributeOptionCombo: String,
        sdkDataValues: List<org.hisp.dhis.android.core.datavalue.DataValue>
    ) {
        // Clear existing data for this instance
        dataValueDao.deleteValuesForInstance(datasetId, period, orgUnit, attributeOptionCombo)

        // Convert SDK data values to Room entities
        val entities = sdkDataValues.map { sdkValue ->
            DataValueEntity(
                datasetId = datasetId,
                period = period,
                orgUnit = orgUnit,
                attributeOptionCombo = attributeOptionCombo,
                dataElement = sdkValue.dataElement() ?: "",
                categoryOptionCombo = sdkValue.categoryOptionCombo() ?: "",
                value = sdkValue.value(),
                comment = sdkValue.comment()
            )
        }

        // Store in Room
        dataValueDao.insertAll(entities)
        Log.d("MetadataCacheService", "Stored ${entities.size} data values in Room database")
    }


    /**
     * Pre-warm caches with commonly used data
     */
    suspend fun preWarmCaches(datasetIds: List<String>) = withContext(Dispatchers.IO) {
        Log.d("MetadataCacheService", "Pre-warming caches for ${datasetIds.size} datasets")

        datasetIds.forEach { datasetId ->
            try {
                getSectionsForDataset(datasetId)
            } catch (e: Exception) {
                Log.w("MetadataCacheService", "Failed to pre-warm cache for dataset $datasetId", e)
            }
        }

        Log.d("MetadataCacheService", "Cache pre-warming completed")
    }
}

/**
 * Data class containing all optimized metadata for EditEntryScreen
 */
data class OptimizedEntryData(
    val sections: List<MetadataCacheService.SectionInfo>,
    val dataElements: Map<String, DataElementEntity>,
    val categoryCombos: Map<String, CategoryComboEntity>,
    val categoryOptionCombos: Map<String, CategoryOptionComboEntity>,
    val orgUnits: Map<String, OrganisationUnitEntity>,
    val sdkDataValues: Map<Pair<String, String>, org.hisp.dhis.android.core.datavalue.DataValue>
)
