# Simple Data Entry — Major Android Product Contribution at HISP Uganda

## Ownership and attribution

Simple Data Entry is a HISP Uganda product. It must not be presented as a product solely owned or originated by Sean Atibuni / Dradebo / Dr. Ash Labs.

During my time at HISP Uganda, I implemented substantial work across the Android user experience, aggregate dataset entry, tracker and event workflows, offline behaviour, organisation-unit handling, synchronisation feedback and the GitHub release pipeline.

- Organisational repository: https://github.com/HISP-Uganda/simpleDataEntry
- Personal development lineage: https://github.com/Dradebo/simpleDataEntry
- Merged contribution PR 1: https://github.com/HISP-Uganda/simpleDataEntry/pull/1
- Merged contribution PR 2: https://github.com/HISP-Uganda/simpleDataEntry/pull/2

## Product context

Simple Data Entry is a native Android DHIS2 client intended for field teams and district data officers working in low-connectivity environments. The current organisational product supports aggregate dataset reporting, standalone event capture and tracker workflows, with Room-backed local persistence and deliberate user-controlled synchronisation.

The application uses Kotlin, Jetpack Compose, the DHIS2 Android SDK and Rules Engine, Hilt, Room, WorkManager, MVVM and StateFlow.

## The problem

DHIS2 users operating in the field may need to navigate large forms, move between aggregate, event and tracker workflows, preserve work through connectivity interruptions and understand when local or remote synchronisation has failed.

The product needed a clearer mobile interaction model without losing the offline behaviour and metadata complexity required by real DHIS2 deployments.

## My contribution

### 1. Large UI and workflow overhaul

I submitted and merged a major UI/UX pull request that reorganised the application flow and affected 210 files across 13 commits.

The work included improvements to navigation, dataset entry surfaces, form organisation and the interaction model used for tracker and event programmes.

Evidence:

- PR: https://github.com/HISP-Uganda/simpleDataEntry/pull/1
- Merge commit: `2869f69f157afab11f1ef3723dd99f23adb398ed`
- Contribution size: 29,595 additions and 9,375 deletions
- Tracker/event restructuring commit: https://github.com/HISP-Uganda/simpleDataEntry/commit/3d181bd16008db85d1c314357a6f2fa727dbd6ee
- Tracker/event model and failed-sync dialogue commit: https://github.com/HISP-Uganda/simpleDataEntry/commit/5e526c3dd10d3b844eb2071811e7d1227fdc7ca3

### 2. DHIS2 workflow integration

The contribution work covered product behaviour across:

- aggregate dataset discovery and entry;
- sectioned and grouped forms;
- tracker enrolment and stage-oriented interfaces;
- standalone event workflows;
- organisation-unit selection;
- local drafts and cached metadata;
- user-visible synchronisation and failure states.

These areas should be described as contributions to the wider HISP Uganda product rather than as independently authored products.

### 3. Release engineering

I also submitted and merged the GitHub release preparation work.

The release workflow handles signing material, APK and AAB generation, and artifact attachment for tagged GitHub releases.

Evidence:

- PR: https://github.com/HISP-Uganda/simpleDataEntry/pull/2
- Merge commit: `979fcafb56c82e3d0827b5adf3eb76e7db7600f7`
- Contribution size: 8 commits, 2 changed files, 52 additions and 43 deletions
- GitHub Release APK/AAB commit: https://github.com/HISP-Uganda/simpleDataEntry/commit/4eb7a6ae19fcb14483dab85efdb6d8b5a9f84d00
- Keystore and artifact upload refactor: https://github.com/HISP-Uganda/simpleDataEntry/commit/e456cf1e0dee5f3b31555946cf86cd652e159201

## Product judgement demonstrated

This work required more than restyling screens. The application had to represent different DHIS2 programme models, preserve field work locally, avoid blocking users unnecessarily during synchronisation and surface failure states in a way that users could act on.

The release work also moved the application from a development repository towards a repeatable Android distribution process.

## Safe portfolio summary

> Simple Data Entry is a HISP Uganda Android product for offline-first DHIS2 data capture. During my time at HISP Uganda, I implemented substantial work across its Compose user experience, aggregate dataset entry, tracker and event workflows, organisation-unit handling, synchronisation feedback and GitHub release pipeline. Two contribution pull requests were merged into the organisational repository, including a 210-file UI/workflow overhaul and the APK/AAB release automation.

## Short card version

**Simple Data Entry — Android Product Contribution at HISP Uganda**

Implemented major Android UI and workflow improvements for an offline-first DHIS2 data-entry product, including aggregate, tracker and event experiences, sync feedback and GitHub APK/AAB release automation.

## Evidence status

- Ownership boundary documented: complete
- Merged PR evidence: complete
- Commit-level feature evidence: complete
- Product screenshots: available in the organisational repository, permission for portfolio reuse still required
- Personal demonstration video: not yet captured
- Employer approval for public case-study wording: recommended before prominent publication
