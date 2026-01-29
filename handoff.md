# Synapse v2 Implementation Handoff

## Project Overview
Synapse is an Android overlay app for handwriting capture that syncs to Obsidian vaults. The MVP (Phases 1-8) was complete before this session. This session implemented all 9 v2 features from `Synapse-Technical-Spec-v2-Features.md`.

## What Was Done

### Implementation Waves (all complete)

**Wave 1 - Foundation (5 parallel agents, ~21 commits):**
- Core data models: `Destination`, `CapturedContext`, `IntentType`, `CostModels`, `LlmConfig`, `QueuedSync`, `SessionSyncConfig`, `TranscriptionModels` extensions
- Cost tracking: `LlmCostCalculator`, `UsageTracker`, `CostDisplay` UI
- Destinations: `ClipboardDestination`, `ShareIntentDestination`, `LocalFolderDestination`, `DestinationRepository`, `DestinationSelector` UI
- Context capture: `SynapseAccessibilityService`, `ProcessTextActivity`, accessibility config XML, manifest updates
- Intent detection: `PromptTemplateV2`, `QuestionAnswerService`, `ReminderManager`, `IntentDialogs` UI, `OutputFormatter`

**Wave 2 - Dependent features (3 agents):**
- Region capture: `RegionGestureDetector` (hold-drag gesture), `RegionCaptureManager`, `ScreenshotManager` (MediaProjection)
- Multi-provider LLM: `LlmProviderFactory` (routes transcription vs answering), `LlmSettingsSection` UI
- Offline queue: `SyncStatusIndicator` + `SyncQueueSummary` UI, `NetworkMonitor`

**Wave 3 - UI integration (2 agents):**
- Context management: `ContextCard` + `ContextSection` composables for review screen
- Onboarding: `AccessibilityPermissionScreen`, `DestinationSetupScreen`

**Wave 4 - Integration (direct):**
- Deleted duplicate `IntentModels.kt` (conflicted with `IntentType.kt`, `CapturedContext.kt`, `TranscriptionModels.kt`)
- Fixed `QuestionAnswerService` to use `LlmProviderFactory` instead of non-existent `getCurrentService()`
- Added `v2Module` to `AppModule.kt` registering all new classes in Koin DI

## Architecture

```
app/src/main/java/com/synapse/
├── api/
│   ├── TranscriptionService.kt        # Interface + TranscriptionServiceFactory
│   ├── TranscriptionServiceFactory.kt  # DefaultTranscriptionServiceFactory singleton
│   ├── GeminiService.kt               # Gemini LLM
│   ├── ClaudeService.kt               # Claude LLM
│   ├── OpenAiService.kt               # OpenAI LLM
│   ├── OllamaService.kt               # Local Ollama
│   ├── LlmProvider.kt                 # LlmProvider enum
│   ├── LlmProviderFactory.kt          # [v2] Routes transcription/answering
│   ├── QuestionAnswerService.kt        # [v2] Q&A via LLM (placeholder)
│   ├── PromptTemplate.kt              # v1 prompt
│   ├── PromptTemplateV2.kt            # [v2] Intent-aware prompt
│   └── TranscriptionModels.kt         # ChunkData, TranscriptionResult, Note, etc.
├── data/
│   ├── cost/
│   │   ├── LlmCostCalculator.kt       # [v2] Token pricing per model
│   │   └── UsageTracker.kt            # [v2] DataStore-backed usage stats
│   ├── destination/
│   │   ├── ClipboardDestination.kt     # [v2] Copy to clipboard
│   │   ├── ShareIntentDestination.kt   # [v2] Android share sheet
│   │   ├── LocalFolderDestination.kt   # [v2] SAF folder write
│   │   └── DestinationRepository.kt    # [v2] Manages destinations
│   ├── repository/                     # Business logic (v1)
│   └── storage/                        # File operations (v1)
├── di/
│   └── AppModule.kt                    # Koin DI - includes v2Module
├── model/
│   ├── CapturedContext.kt              # [v2] Sealed: SelectedText, RegionText, RegionImage, AutoContext
│   ├── CostModels.kt                  # [v2] CostEstimate, UsageStats, TokenPricing
│   ├── Destination.kt                 # [v2] Interface + SyncContent, SyncResult, SyncError
│   ├── IntentType.kt                  # [v2] IntentType enum, DetectedIntent, IntentData
│   ├── LlmConfig.kt                   # [v2] Multi-provider config
│   ├── QueuedSync.kt                  # [v2] QueueStatus, QueuedSync
│   ├── SessionSyncConfig.kt           # [v2] Per-session destination config
│   ├── TranscriptionModels.kt         # TranscribedNote (with intent), QuestionWithAnswer, ProcessedResult
│   └── (v1 models: Chunk, Session, Project, Settings, SyncStatus)
├── service/
│   ├── SynapseAccessibilityService.kt  # [v2] Context capture via accessibility
│   ├── RegionCaptureManager.kt         # [v2] Text extraction from screen regions
│   ├── ScreenshotManager.kt           # [v2] MediaProjection screen capture
│   ├── ReminderManager.kt             # [v2] Alarm/calendar intents
│   ├── OverlayService.kt              # v1 overlay
│   ├── CaptureService.kt              # v1 capture
│   └── NotificationHelper.kt          # v1 notifications
├── ui/
│   ├── components/
│   │   ├── ContextCard.kt             # [v2] Context display in review
│   │   ├── CostDisplay.kt             # [v2] Cost banner + usage stats
│   │   ├── DestinationSelector.kt     # [v2] Destination picker
│   │   ├── IntentDialogs.kt           # [v2] Intent confirmation, Q&A, reminder dialogs
│   │   ├── LlmSettingsSection.kt      # [v2] Multi-provider settings UI
│   │   └── SyncStatusIndicator.kt     # [v2] Sync queue status UI
│   ├── onboarding/
│   │   ├── AccessibilityPermissionScreen.kt  # [v2] Accessibility setup step
│   │   ├── DestinationSetupScreen.kt         # [v2] Destination choice step
│   │   └── (v1 onboarding screens)
│   ├── overlay/
│   │   ├── RegionGestureDetector.kt    # [v2] Hold-drag region selection
│   │   └── (v1 canvas, stroke manager)
│   └── (v1 screens: review, settings, navigation)
├── util/
│   ├── NetworkMonitor.kt              # [v2] Connectivity tracking
│   ├── OutputFormatter.kt             # [v2] Context-aware markdown output
│   └── PermissionHelper.kt            # v1
└── ProcessTextActivity.kt             # [v2] ACTION_PROCESS_TEXT handler
```

## Known Issues / Incomplete Items

### Must Fix Before Release
1. **QuestionAnswerService is a placeholder** - Returns static string. Needs text-only LLM endpoint (current services are image-based). Location: `api/QuestionAnswerService.kt:40`
2. **ScreenshotManager needs MediaProjection permission flow** - The manager exists but there's no UI to request screen capture permission from the user. Needs an Activity result launcher integration.
3. **Onboarding screens not wired into NavGraph** - `AccessibilityPermissionScreen` and `DestinationSetupScreen` are created but not added to `NavGraph.kt` or `OnboardingScreen.kt` flow.
4. **v2 UI components not integrated into existing screens** - Components like `CostDisplay`, `SyncStatusIndicator`, `ContextCard`, `DestinationSelector`, `LlmSettingsSection`, and `IntentDialogs` are built but need to be placed into `ReviewScreen.kt`, `SettingsScreen.kt`, etc.
5. **RegionGestureDetector not integrated into overlay** - The gesture detector exists but isn't hooked into `CaptureCanvas.kt` or `OverlayService.kt`.

### Model Reconciliation Notes
- `CapturedContext.kt` (canonical): Has `id`, `timestamp` abstract fields, `Rect bounds` on region types, `imagePath: String` for RegionImage, `pageTitle` on AutoContext
- `OutputFormatter.kt` references `CapturedContext.RegionImage.description` which exists
- `IntentType.kt` (canonical): `DetectedIntent` uses field name `extractedData` (not `data`). Has extra fields: `TaskData.taskText`, `QuestionData.answer`, `ReminderData.reminderText`
- `TranscriptionModels.kt`: `TranscribedNote.intent` defaults to `DetectedIntent(type = IntentType.NOTE, confidence = 1.0f)` - constructs with named params, compatible

### Nice to Have
- `LlmCostCalculator` is a singleton object (not class) - registered as `single { LlmCostCalculator }` in DI which works but is redundant
- Legacy `dataModule` and `networkModule` in AppModule.kt could be removed

## Key Files for Next Steps
- **To wire onboarding**: `ui/onboarding/OnboardingScreen.kt`, `ui/navigation/NavGraph.kt`
- **To wire review UI**: `ui/review/ReviewScreen.kt`, `ui/review/ReviewViewModel.kt`
- **To wire settings UI**: `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`
- **To wire overlay region capture**: `ui/overlay/CaptureCanvas.kt`, `service/OverlayService.kt`
- **To add MediaProjection flow**: `ui/MainActivity.kt` (add ActivityResultLauncher)

## Build Status
Run `./gradlew.bat assembleDebug` to verify. As of this handoff, the build should compile (duplicate models resolved, DI registered).

## Git State
- Branch: `main`
- Total commits: ~55
- v2 commits: ~25 (from "Add cost model data classes" through "Add v2 DI module")
