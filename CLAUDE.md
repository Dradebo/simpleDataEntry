# SimpleDataEntry DHIS2 Android App - Claude Context

**Last Updated**: 2026-01-20
**Status**: Production-ready for datasets and tracker enrollments. Login flow resilient. Nested accordion data entry fully functional. Implied grouping supports complex patterns.

## Project Overview

**SimpleDataEntry** is an Android application for DHIS2 data collection with offline-first architecture. Supports aggregate datasets, tracker programs, and event programs using DHIS2 Android SDK v2.7.0+.

## Current Implementation Status

### ✅ WORKING FEATURES

**Dataset Data Entry** (Aggregate):
- Complete CRUD operations for dataset instances
- Offline data entry with sync
- Period and org unit selection
- CategoryOptionCombo support
- ✅ **Nested accordion rendering**: Data elements → Category options → Entry fields
- ✅ **Polished UI styling**: White cards with left accent, lavender nested accordions
- ✅ **Has data/No data indicators**: Visual feedback on accordion headers

**Tracker Program Support**:
- Enrollment listing in table/pivot view (`TrackerEnrollmentTableScreen`)
- Column customization and persistence
- Search and sort functionality
- 22 enrollments displaying correctly in test instance

**Event Data Capture**:
- Event data entry screen (`EventCaptureScreen`) fully functional
- ✅ **Option sets working**: Dropdowns, radio buttons, YES/NO buttons based on data element configuration
- ✅ **Text entry bug fixed**: Cursor position preserved (no more backwards typing)
- Data values save correctly

**Authentication & Account Management**:
- Online/offline login with SHA-256 password validation
- Secure credential storage
- ✅ **Multiple account support**: Each account has separate Room database (simpleDataEntry-11)
- ✅ **Data isolation**: No cross-contamination between accounts via separate `room_{accountId}.db` files
- ✅ **Saved accounts shared**: All saved accounts visible across login/settings (uses shared database)
- ✅ **Resilient login flow**: Metadata download retries up to 3 times with user-friendly error messages

### ⚠️ BROKEN/INCOMPLETE

**EventsTableScreen** (`presentation/tracker/EventsTableScreen.kt`):
- ❌ **Line 126**: FAB navigation route `"EventCapture/$programId/null/null/null"` DOES NOT EXIST
  - Should be: `"CreateEvent/$programId/$programName"`
- ❌ **Line 186-189**: Edit navigation route format doesn't match any actual route
  - Should be: `"EditStandaloneEvent/$programId/$programName/${row.id}"`

**EventsTableViewModel** (`presentation/tracker/EventsTableViewModel.kt`):
- ⚠️ **Line 113**: Redundant `.filterIsInstance<ProgramInstance.EventInstance>()` - data already correct type
- ⚠️ **Lines 164-172**: `blockingGet()` called in Flow/coroutine context - will block thread
- ⚠️ **Performance issue**: `buildColumns()` called on every search/sort, re-fetching data element names repeatedly

**Program Rules**:
- Evaluation code commented out in `EventCaptureViewModel.kt` (lines 324-395)
- TODO markers for future implementation with correct DHIS2 SDK APIs

## Architecture

### Account Isolation (NEW - 2025-12-04)
- **`AccountManager`** (`data/AccountManager.kt`): Manages multiple DHIS2 accounts
  - Generates stable MD5-based account IDs from `username@serverUrl`
  - Stores account metadata in SharedPreferences (JSON)
  - Each account gets unique database names: `room_{accountId}.db`
- **`DatabaseManager`** (`data/DatabaseManager.kt`): Account-specific Room database lifecycle
  - Thread-safe database switching with Mutex
  - Closes old database, opens new one on account change
  - Only ONE Room database open at a time (memory efficient)
- **Critical**: D2 SDK database is SHARED (API limitation), Room database is ISOLATED per account

### Core Models
- **`ProgramInstance`** (sealed class): Type-safe representation of datasets, tracker enrollments, and events
- **`TrackedEntityDataValue`**: Event data values (dataElement, value, event)
- **`TrackedEntityAttributeValue`**: Tracker enrollment attributes (id, displayName, value)

### Key Navigation Routes
From `AppNavigation.kt`:
```kotlin
// Datasets
"DatasetInstances/{datasetId}/{datasetName}"

// Tracker enrollments (table view)
"TrackerEnrollments/{programId}/{programName}"

// Events (BROKEN - needs fixing)
"EventsTable/{programId}/{programName}"

// Create/Edit Events
"CreateEvent/{programId}/{programName}"
"CreateEvent/{programId}/{programName}/{programStageId}"
"EditStandaloneEvent/{programId}/{programName}/{eventId}"
"EditEvent/{programId}/{programName}/{eventId}/{enrollmentId}"
```

### Repository Pattern
- `DatasetInstancesRepository`: Interface for all program instance operations
- `DatasetInstancesRepositoryImpl`: Implementation using DHIS2 SDK
- Returns `Flow<List<ProgramInstance>>` for reactive data loading

## Critical Development Rules

### NEVER Do These
1. ❌ Use `blockingGet()` in coroutine/Flow contexts - causes ANR
2. ❌ Call database operations repeatedly in loops (cache instead)
3. ❌ Navigate to routes without verifying they exist in `AppNavigation.kt`
4. ❌ Claim features work without building and testing
5. ❌ Break existing working functionality when adding features

### ALWAYS Do These
1. ✅ Verify navigation routes in `AppNavigation.kt` before using
2. ✅ Use suspending coroutines for database operations
3. ✅ Test builds with `./gradlew assembleDebug` before claiming completion
4. ✅ Use `.flowOn(Dispatchers.IO)` not `withContext` for Flow operations
5. ✅ Read existing working code patterns before implementing new features
6. ✅ **Use Beads for ALL issue tracking and documentation** - NO new markdown files in project
7. ✅ Check Beads issues for current status and context before starting work

### Blackbox (Do Not Touch Without Profiling)
1. 🔒 **Category parsing for EditEntryScreen**: must be derived from metadata form structure, not data values.
   - Location: `app/src/main/java/com/ash/simpledataentry/presentation/dataEntry/DataEntryViewModel.kt`
   - Reason: changes here have repeatedly caused ANRs and category mapping regressions.

## Recent Session Work (2025-12-17)

### COMPLETED - Nested Accordion Data Entry System ✅
**Plan File**: `/Users/sean/.claude/plans/drifting-bouncing-orbit.md`
**Status**: ✅ Fully functional with polished UI styling

#### Problem Statement
Dataset data entry with category combinations (disaggregations) was rendering incorrectly:
- Entry fields appeared as a flat list instead of nested accordions
- Category option combo lookups failed, resulting in empty entry fields
- Room database was being wiped during offline login/session restoration

#### Root Cause Analysis (Multi-Layer Issue)

**Layer 1: Stale DAO References**
- `MetadataCacheService` and `DataEntryRepositoryImpl` were `@Singleton` scoped
- They captured Room DAOs at construction time via dependency injection
- When accounts switched, the cached DAOs still pointed to the old/fallback database
- Result: Service reported 0 dataElements/categoryOptionCombos even though Room had data

**Layer 2: Room Database Hydration**
- `hydrateRoomFromSdk()` cleared Room tables before inserting SDK data
- During offline login, SDK returns empty data (can't fetch from server)
- Result: Room was wiped clean, losing all cached metadata

**Layer 3: Missing Data Element Accordion Wrapper**
- `SectionContent` was calling `CategoryAccordionRecursive` directly with categories
- The data element should be the FIRST accordion level, with categories nested inside
- Result: First category OPTIONS appeared as top-level accordions instead of data elements

**Layer 4: Combo UID Lookup Path Mismatch**
- `parentPath` included element key prefix (e.g., `["element_abc123", "gradeUid", "sexUid"]`)
- `optionUidsToComboUid` map was keyed by option UIDs only (e.g., `setOf("gradeUid", "sexUid")`)
- Result: Lookup always failed, `filteredValues` was always empty

#### Solutions Implemented

**Fix 1: Dynamic DAO Access** (`MetadataCacheService.kt`, `DataEntryRepositoryImpl.kt`)
```kotlin
// Before: DAOs captured at construction (stale after account switch)
@Singleton
class MetadataCacheService @Inject constructor(
    private val dataElementDao: DataElementDao,  // Captured once!
    ...
)

// After: Dynamic DAO access via DatabaseProvider
@Singleton
class MetadataCacheService @Inject constructor(
    private val databaseProvider: DatabaseProvider
) {
    // Always gets current database's DAO
    private val dataElementDao: DataElementDao
        get() = databaseProvider.getCurrentDatabase().dataElementDao()
}
```

**Fix 2: Protected Room Hydration** (`SessionManager.kt`)
```kotlin
// Only clear and re-insert if SDK returns data
if (dataElements.isNotEmpty()) {
    db.dataElementDao().clearAll()
    db.dataElementDao().insertAll(dataElements)
} else {
    Log.w("SessionManager", "SDK returned 0 dataElements - preserving existing Room data")
}
```

**Fix 3: Data Element Accordion Wrapper** (`EditEntryScreen.kt`)
```kotlin
// SectionContent now wraps each data element as FIRST accordion level
dataElements.forEach { (dataElement, dataElementName) ->
    DataElementAccordion(
        header = dataElementName,
        hasData = hasData,
        expanded = isExpanded,
        onToggleExpand = { onToggle(emptyList(), elementKey) }
    ) {
        CategoryAccordionRecursive(
            categories = structure,
            values = dataElementValues,
            parentPath = listOf(elementKey),  // Include element in path
            ...
        )
    }
}
```

**Fix 4: Option-Only Path for Combo Lookup** (`EditEntryScreen.kt`)
```kotlin
// Helper filters out element_ prefix for combo UID lookup
fun optionOnlyPath(path: List<String>): Set<String> {
    return path.filter { !it.startsWith("element_") }.toSet()
}

// Usage: Only use option UIDs for the lookup
val comboUid = optionUidsToComboUid[optionOnlyPath(fullPath)]
```

#### Expected Accordion Hierarchy
```
Section Header (e.g., "B: Enrolment Information")
└── Data Element accordion (e.g., "Grade 2 Enrolment") - WHITE card, left accent
    └── Age Category accordion (e.g., "<7", "7", "8") - LAVENDER background
        └── Sex columns (Male | Female) - Entry fields
```

#### UI Styling (Polished Version)

**DataElementAccordion** (first level):
- White card background with subtle shadow
- Left accent border (pink/primary if has data, muted if no data)
- "Has data" / "No data" indicator text
- Bold title with chevron

**CategoryAccordion** (nested levels):
- Light lavender/purple background (`surfaceVariant`)
- Simpler, smaller styling
- Medium font weight

#### Files Modified
1. `MetadataCacheService.kt` - Dynamic DAO access via DatabaseProvider
2. `DataEntryRepositoryImpl.kt` - Dynamic DAO access via DatabaseProvider
3. `AppModule.kt` - Updated provider methods for new constructor signatures
4. `SessionManager.kt` - Protected Room hydration, diagnostic logging
5. `EditEntryScreen.kt` - Data element wrapper, option-only path lookup, styling

---

### COMPLETED - Login Flow Resilience (DHIS2 Capture App Pattern) ✅
**Plan File**: `/Users/sean/.claude/plans/parsed-cooking-taco.md`
**Status**: ✅ Implemented, verified working

#### Design Philosophy: Matching Official DHIS2 Android Capture App

The official DHIS2 Android Capture app handles unreliable metadata downloads gracefully. Our implementation now mirrors their approach:

1. **Incremental Metadata Download**: SDK saves metadata as it downloads (not transactional)
2. **Retry on Failure**: Up to 3 retry attempts with delays
3. **Usable Metadata Check**: Verify essential data exists after each attempt
4. **User-Friendly Errors**: Translate technical errors to actionable messages

#### Problem Solved
Login completes but metadata/data doesn't download on some DHIS2 servers:
- Metadata download failed at 41% with "NullPointerException: Null attribute"
- UI showed nothing after login (empty dataset list)
- BackgroundSyncWorker failed to instantiate due to WorkManager/Hilt conflict

#### Root Cause Analysis
- SDK metadata download is **incremental** (not transactional) - saves as it goes
- When error occurred at 41%, OrgUnits/Programs/Datasets hadn't been downloaded yet
- Official DHIS2 Capture app uses retry logic and `WAS_INITIAL_SYNC_DONE` flag pattern
- WorkManager's default initializer was running before Hilt could provide workers

#### Fixes Applied

**1. Resilient Metadata Download** (`SessionManager.kt`):
```kotlin
private suspend fun downloadMetadataResilient(): Result<Unit> {
    var lastError: Throwable? = null
    repeat(3) { attempt ->
        try {
            d2.metadataModule().download().await()

            // Check if we got usable metadata
            val hasOrgUnits = d2.organisationUnitModule().organisationUnits()
                .blockingCount() > 0
            val hasPrograms = d2.programModule().programs().blockingCount() > 0
            val hasDatasets = d2.dataSetModule().dataSets().blockingCount() > 0

            if (hasOrgUnits || hasPrograms || hasDatasets) {
                return Result.success(Unit)  // Success!
            }
        } catch (e: Exception) {
            lastError = e
            if (attempt < 2) {
                updateProgress("Retrying... (attempt ${attempt + 2})")
                delay(2000)
            }
        }
    }
    return Result.failure(lastError ?: Exception("Metadata download failed"))
}
```

**2. User-Friendly Error Messages** (`SessionManager.kt`):
| Technical Error | User Message |
|----------------|--------------|
| "Null attribute" | "Server has invalid metadata configuration. Contact administrator." |
| Timeout | "Connection timed out. Please check your internet connection." |
| Unable to resolve host | "Cannot reach server. Check your connection and server URL." |
| 401/Unauthorized | "Authentication failed. Please check your credentials." |

**3. WorkManager/Hilt Integration** (`AndroidManifest.xml`):
```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

#### Session Restoration Flow
```
App Launch
├── Check for existing session (SharedPreferences)
├── If session exists:
│   ├── Restore D2 instance
│   ├── Check Room database has data
│   │   ├── If empty: Re-hydrate from SDK cache
│   │   └── If has data: Use existing data
│   └── Navigate to home screen
└── If no session: Show login screen
```

---

### Recent Work (2026-01-20)

**Implied Grouping Enhancement** - Hyphen-Suffix Pattern Support:
- **File**: `domain/grouping/ImpliedCategoryInferenceService.kt`
- Added `HYPHEN_SUFFIX_GENDER_REGEX` pattern for complex data element names
- Supports patterns like `WFP - Number of teachers in the school-Qualified Female`
- Creates 3-level hierarchy: Indicator → Qualifier → Gender
- New method: `tryInferWithHyphenSuffixGender()` with mapping support

**Build Configuration** - CI Compatibility:
- **File**: `settings.gradle.kts` - Added foojay-resolver-convention plugin for toolchain auto-download
- **File**: `local.properties` - Contains `org.gradle.java.home` for local development (gitignored)
- System Java 24 not compatible with Gradle 8.11.1; use Android Studio's bundled JDK 21
- CI uses GitHub Actions Java 21 + foojay resolver

### Known Issues (2026-01-20)

**Validation Stack Overflow** (`domain/validation/ValidationService.kt`):
- ⚠️ DHIS2 SDK validation engine hits StackOverflowError on complex rule expressions
- Current behavior: Catches error and returns warning, allows completion
- Root cause: Deep recursion in SDK expression parser (stack size 1035KB insufficient)
- Potential fix: Run validation on thread with larger stack (not yet implemented)
- Log signature: `"DHIS2 SDK validation engine stack overflow: stack size 1035KB"`

---

### Previous Work (2025-12-11)

**Design System Bug Fixes** (2026-01-09):
- Replaced BrandGreen gradients/accents with DHIS2Blue across login/loading/create entry/tracker screens
- Added missing LOADING_DATA and PROCESSING_DATA progress phases in login flow
- Fixed dataset instance status badges to show "Up to date" for synced items
- Build verified with `./gradlew assembleDebug`

**Entry Combo Defaults** (2026-01-09):
- Entry form hides "default" attribute/category combinations from the title and category accordion
- Create entry attribute option combo dropdown is disabled when only "default" exists

**Entry Form UX Polish** (2026-01-09):
- Step-based loading screens wired for entry and sync overlays
- Entry shimmer cards now use surfaceVariant (no pink tint)
- Section/subsection navigation now opens nested accordions and auto-expands the first nested accordion per section
- Section/subsection navigation arrows repositioned closer to titles for clarity
- Subsection navigation suppressed for radio/checkbox groups and generic “Related fields” groups
- Section accordion visuals updated to a nested card layout (top-level card with deeper padding for nested levels)

**Dataset Race Condition Fix** (simpleDataEntry-19):
- Changed `SessionManager.kt`: `.apply()` → `.commit()` for synchronous SharedPreferences write
- Fixed cache validation clearing datasets from Room database

**Account Isolation** (simpleDataEntry-11):
- `AccountManager.kt` - Multi-account management with MD5-based stable IDs
- `DatabaseManager.kt` - Account-specific Room database lifecycle
- D2 SDK database shared, Room database isolated per account

### Known Issues (Lower Priority)
1. ❌ `EventsTableScreen.kt` - Navigation routes broken (lines 126, 186-189)
2. ❌ `EventsTableViewModel.kt` - Blocking calls and performance issues (lines 113, 164-172)

## Test Instance Status
- User: `android@emisuganda.org` (or `adilanghciii`) with offline access
- 1 tracker program: `QZkuUuLedjh` with 22 enrollments working correctly
- Datasets: Working after race condition fix
- Login flow: Resilient across different DHIS2 server configurations

## Build Status
✅ Code compiles successfully (verified 2025-12-17)
✅ Nested accordion data entry fully functional with polished UI
✅ Login flow resilience implemented and verified working
✅ Account isolation implementation complete
✅ Dataset race condition fix verified
✅ Dynamic DAO access prevents stale reference issues
⚠️ EventsTable navigation still broken (lower priority)

## Documentation Policy
**CRITICAL FOR ALL SESSIONS**:
- ✅ Use Beads issues for ALL documentation and tracking
- ❌ DO NOT create new markdown files in the project
- ✅ Update Beads design/notes fields with implementation details
- ✅ Check existing Beads issues before starting work
- Reference: Architectural audit in `/Users/sean/.claude/plans/iridescent-rolling-feigenbaum.md`

## Next Priority Work
From architectural audit (see Beads issues simpleDataEntry-12 through simpleDataEntry-18):
1. **Priority 1 Remaining**:
   - simpleDataEntry-12: Implement SyncStatusController
   - simpleDataEntry-13: Global Top Bar Progress Indicator

2. **Priority 2**:
   - simpleDataEntry-14: Shimmer Placeholders
   - simpleDataEntry-15: Live Count Updates
   - simpleDataEntry-16: Per-Program Progress Display

3. **Priority 3**:
   - simpleDataEntry-17: WorkManager Migration
   - simpleDataEntry-18: WorkManager Progress Integration
