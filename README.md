# Synapse

**Zero-friction handwriting capture for Obsidian.**

Synapse is an Android overlay app that lets you capture handwritten notes without leaving your current app. Scribble quick thoughts, and later sync them to your Obsidian vault as clean, formatted markdown -- transcribed by your choice of LLM.

## How It Works

1. **Tap the floating bubble** -- appears over any app
2. **Scribble your notes** -- on a transparent fullscreen canvas
3. **Auto-chunks after 3s** -- keeps capturing as you write
4. **Tap Done** -- session ends
5. **Review & Sync** -- select destination, transcribe via LLM, sync to vault

## Features

### Core Capture
- **Floating Overlay** -- capture notes without switching apps
- **Automatic Chunking** -- 3-second timeout creates natural breaks between strokes
- **Smart Transcription** -- messy handwriting to clean markdown via LLM
- **Mermaid Diagrams** -- hand-drawn flowcharts converted to Mermaid code (advanced formatting mode)
- **Undo Support** -- undo last stroke while capturing

### Multi-Provider LLM
- **Gemini** -- Google's Gemini 1.5 Flash (free tier available)
- **Claude** -- Anthropic's Claude 3 Haiku
- **OpenAI** -- GPT-4o Mini
- **Ollama** -- local LLaVA for fully offline transcription
- **Separate providers** -- configure different LLMs for transcription (image-based) vs. question answering (text-based)

### Context Capture
- **Text Selection** -- select text in any app, share it to Synapse via Android's text processing
- **Region Capture** -- hold-and-drag to select a screen region, extract text via accessibility or screenshot
- **Auto Context** -- captures active app info via accessibility service
- **Context-Aware Output** -- captured context is woven into the transcription prompt for smarter results

### Intent Detection
- **Automatic Classification** -- LLM detects if your note is a plain note, task, question, or reminder
- **Question Answering** -- detected questions are answered inline using the configured LLM
- **Reminder Creation** -- detected reminders prompt to set alarms or calendar events
- **Task Extraction** -- tasks are formatted with checkboxes in markdown output

### Multiple Destinations
- **Obsidian Vault** -- sync via Storage Access Framework with persistent URI permissions
- **Local Folder** -- write to any folder on device via SAF
- **Clipboard** -- copy transcribed text directly
- **Share Sheet** -- send to any app via Android's share intent
- **Per-Session Config** -- choose destination(s) for each sync session

### Cost Tracking
- **Token Pricing** -- tracks estimated cost per LLM call based on model pricing
- **Usage Statistics** -- cumulative stats stored in DataStore (total tokens, total cost, call count)
- **Cost Display** -- banner in review screen shows estimated sync cost before you commit

### Offline Support
- **Network Monitor** -- detects connectivity changes in real time
- **Sync Queue** -- failed syncs are queued with status tracking (pending, syncing, completed, failed)
- **Queue Summary** -- UI shows pending/queued/failed counts with retry controls

## Requirements

- Android 8.0+ (API 26)
- Stylus recommended (works with finger too)
- Obsidian vault on device storage (for vault sync)
- API key for at least one cloud LLM provider, or Ollama running locally

## Permissions

| Permission | Purpose |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Floating overlay bubble and capture canvas |
| `FOREGROUND_SERVICE` | Keep capture service alive during sessions |
| `POST_NOTIFICATIONS` | Foreground service notification |
| `INTERNET` | LLM API calls and sync |
| `ACCESS_NETWORK_STATE` | Offline detection for sync queue |
| `VIBRATE` | Haptic feedback on region selection |
| `RECEIVE_BOOT_COMPLETED` | Restore state after reboot |
| Accessibility Service | Text extraction from screen regions, auto-context capture |
| MediaProjection | Screenshot-based region capture (requested on demand) |
| SAF (document access) | Read/write Obsidian vault and local folders |

## Installation

### Download
See [Releases](https://github.com/Pilves/synapse/releases).

### Build from Source
```bash
git clone https://github.com/Pilves/synapse.git
cd synapse
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Or open in Android Studio and run directly on a connected device.

## Setup

The onboarding flow walks through these steps:

1. **Overlay Permission** -- draw over other apps
2. **Accessibility Permission** -- enable Synapse accessibility service for context capture
3. **Vault Selection** -- pick your Obsidian vault folder
4. **Destination Setup** -- choose default sync destinations
5. **API Key** -- enter key for your preferred LLM provider

After onboarding, configure additional options in Settings:
- Switch between LLM providers for transcription and answering separately
- Toggle advanced formatting (Mermaid diagrams, LaTeX)
- Adjust capture timeout, fade animation, auto-end timer
- Manage projects (subfolders in your vault)

## Architecture

```
app/src/main/java/com/synapse/
├── api/                          # LLM service layer
│   ├── TranscriptionService.kt   # Interface (transcribe images + text query)
│   ├── GeminiService.kt          # Google Gemini implementation
│   ├── ClaudeService.kt          # Anthropic Claude implementation
│   ├── OpenAiService.kt          # OpenAI implementation
│   ├── OllamaService.kt          # Local Ollama implementation
│   ├── LlmProviderFactory.kt     # Routes to correct provider by task type
│   ├── QuestionAnswerService.kt   # Q&A via text-based LLM calls
│   ├── PromptTemplate.kt         # Base transcription prompt
│   └── PromptTemplateV2.kt       # Intent-aware prompt with context
├── data/
│   ├── cost/
│   │   ├── LlmCostCalculator.kt  # Token pricing per model
│   │   └── UsageTracker.kt       # DataStore-backed usage statistics
│   ├── destination/
│   │   ├── ClipboardDestination.kt
│   │   ├── ShareIntentDestination.kt
│   │   ├── LocalFolderDestination.kt
│   │   └── DestinationRepository.kt
│   ├── repository/                # Business logic (sessions, projects)
│   └── storage/                   # File I/O (WebP, metadata, stitching)
├── di/
│   └── AppModule.kt              # Koin DI modules
├── model/
│   ├── CapturedContext.kt         # Sealed class: SelectedText, RegionText, RegionImage, AutoContext
│   ├── CostModels.kt             # CostEstimate, UsageStats, TokenPricing
│   ├── Destination.kt            # Destination interface + SyncContent, SyncResult
│   ├── IntentType.kt             # NOTE, TASK, QUESTION, REMINDER + DetectedIntent
│   ├── LlmConfig.kt              # Multi-provider configuration
│   ├── QueuedSync.kt             # Offline queue data model
│   ├── SessionSyncConfig.kt      # Per-session destination selection
│   └── TranscriptionModels.kt    # TranscribedNote, ProcessedResult, ChunkData
├── service/
│   ├── OverlayService.kt         # Floating bubble + capture canvas + region mode
│   ├── CaptureService.kt         # Stroke capture and chunking
│   ├── SynapseAccessibilityService.kt  # Screen text extraction
│   ├── RegionCaptureManager.kt   # Region text/image extraction pipeline
│   ├── ScreenshotManager.kt      # MediaProjection screen capture
│   ├── MediaProjectionHolder.kt  # Activity-Service bridge for projection consent
│   ├── ReminderManager.kt        # Alarm and calendar intent creation
│   ├── NotificationHelper.kt     # Foreground service notifications
│   └── NetworkMonitor.kt         # Connectivity state tracking
├── ui/
│   ├── components/
│   │   ├── ContextCard.kt        # Context display cards in review
│   │   ├── CostDisplay.kt        # Cost banner and usage stats
│   │   ├── DestinationSelector.kt # Destination picker chips
│   │   ├── IntentDialogs.kt      # Intent confirmation, Q&A, reminder dialogs
│   │   ├── LlmSettingsSection.kt # Multi-provider settings UI
│   │   └── SyncStatusIndicator.kt # Queue status display
│   ├── navigation/               # NavGraph, Screen sealed class
│   ├── onboarding/               # Welcome, permissions, vault, destination, API key
│   ├── overlay/
│   │   ├── CaptureCanvas.kt      # Drawing surface + region gesture detection
│   │   └── RegionGestureDetector.kt # Hold-drag gesture recognizer
│   ├── review/                   # Session list, chunk management, sync
│   └── settings/                 # Provider config, capture options, projects
├── util/
│   ├── OutputFormatter.kt        # Context-aware markdown formatting
│   └── PermissionHelper.kt       # Runtime permission utilities
└── ProcessTextActivity.kt        # Android ACTION_PROCESS_TEXT handler
```

## Tech Stack

| Component | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| DI | Koin |
| State | ViewModel + StateFlow |
| Storage | DataStore, SAF, WebP |
| Networking | Ktor, OkHttp |
| Images | Coil |
| Min SDK | API 26 (Android 8.0) |
| Target SDK | Latest stable |
| JVM | Java 17 |

## Contributing

Contributions welcome:

1. Check existing issues first
2. Open an issue to discuss major changes
3. Fork, branch, PR
4. Test on a real device (overlay and accessibility features need physical hardware)

## License

MIT License -- see [LICENSE](LICENSE).
