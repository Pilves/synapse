# Synapse

**Zero-friction handwriting capture for Obsidian.**

Synapse is an Android overlay app that lets you capture handwritten notes without leaving your current app. Scribble quick thoughts, and later sync them to your Obsidian vault as clean, formatted markdown — transcribed by your choice of LLM.

## Features

- **Floating overlay** — capture notes over any app without switching
- **Automatic chunking** — configurable timeout (1-10s) creates natural breaks between strokes
- **Smart transcription** — messy handwriting to clean markdown via LLM
- **Mermaid diagrams** — hand-drawn flowcharts converted to Mermaid code (advanced formatting mode)
- **Context capture** — select text, capture screen regions, or auto-capture active app info
- **Obsidian vault sync** — transcribed notes appended as markdown via SAF
- **Multi-provider LLM** — Gemini, Claude, OpenAI, or local Ollama
- **Separate providers** — configure different LLMs for transcription (vision) vs. question answering (text)
- **Cost tracking** — estimated cost per sync, cumulative usage stats
- **Offline queue** — failed syncs are queued with retry controls
- **Prompt customization** — edit the transcription prompt template in-app
- **Undo support** — undo last stroke while capturing
- **Palm rejection** — filters accidental palm touches for stylus/tablet users
- **Stroke smoothing** — Catmull-Rom splines for cleaner ink paths
- **Haptic feedback** — vibration feedback on region selection and confirmation dialogs
- **Encrypted API key storage** — keys stored with AES-256-GCM via EncryptedSharedPreferences
- **Project/notebook organization** — group sessions into projects for structured note management

## Installation

### Download

See [Releases](https://github.com/Pilves/synapse/releases).

### Build from Source

```bash
git clone https://github.com/Pilves/synapse.git
cd synapse

# Linux/macOS
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug

# APK at app/build/outputs/apk/debug/app-debug.apk
```

Or open in Android Studio and run directly on a connected device.

## Quick Start

1. **Install & open** — launch Synapse, walk through the onboarding screens
2. **Grant permissions** — allow "Draw over apps" and enable the accessibility service
3. **Pick your vault** — select your Obsidian vault folder (or any folder)
4. **Add an API key** — enter a key for Gemini, Claude, or OpenAI (or set up Ollama locally)
5. **Start capturing** — tap the floating bubble, scribble, tap Done, review & sync

> **Tip:** Gemini offers a free tier — the onboarding screen links directly to get a key.

## Configuration

### LLM Providers

| Provider | Model | API Key Required | Offline | Notes |
|---|---|---|---|---|
| Google Gemini | `gemini-2.0-flash` | Yes | No | Free tier available |
| Anthropic Claude | `claude-3-5-haiku-20241022` | Yes | No | |
| OpenAI | `gpt-4o-mini` | Yes | No | |
| Ollama | `llava` | No | Yes | Requires Ollama running on local network |

You can configure **separate providers** for transcription (image-based) and question answering (text-based) in Settings.

<details>
<summary><strong>Gemini setup</strong></summary>

1. Go to [Google AI Studio](https://aistudio.google.com/apikey) and create an API key
2. In Synapse, open **Settings**
3. Select **Google Gemini** as your provider
4. Paste your API key
5. Tap **Save**

</details>

<details>
<summary><strong>Claude setup</strong></summary>

1. Go to [Anthropic Console](https://console.anthropic.com/) and create an API key
2. In Synapse, open **Settings**
3. Select **Anthropic Claude** as your provider
4. Paste your API key
5. Tap **Save**

</details>

<details>
<summary><strong>OpenAI setup</strong></summary>

1. Go to [OpenAI Platform](https://platform.openai.com/api-keys) and create an API key
2. In Synapse, open **Settings**
3. Select **OpenAI** as your provider
4. Paste your API key
5. Tap **Save**

</details>

<details>
<summary><strong>Ollama setup (local/offline)</strong></summary>

1. Install [Ollama](https://ollama.ai/) on a machine on your local network
2. Pull the LLaVA model: `ollama pull llava`
3. Start the Ollama server (default port 11434)
4. In Synapse, open **Settings**
5. Select **Ollama (Local)** as your provider
6. Enter the server URL (e.g. `http://192.168.1.100:11434`)
7. No API key needed

</details>

### Capture Settings

| Setting | Default | Range |
|---|---|---|
| Chunk timeout | 1 second | 1-10 seconds |
| Session auto-end | 15 minutes | 5-60 minutes |
| Fade animation | 0.3 seconds | 0-1 second |
| Rate limiting | Fast | Safe / Fast |
| Advanced formatting | On | On (Mermaid, LaTeX) / Off |

## Usage

### Capturing Notes

1. **Tap the floating bubble** — a transparent fullscreen canvas appears over your current app
2. **Write your notes** — use stylus or finger on the canvas
3. **Auto-chunking** — after the chunk timeout (default 1s of inactivity), a chunk is captured and the canvas clears for more writing
4. **Tap Done** — ends the session and opens the review screen

### Context Capture

- **Text selection** — select text in any app, then share it to Synapse via Android's text processing menu. The selected text is included as context in the transcription prompt.
- **Region capture** — hold and drag to select a screen region. Text is extracted via the accessibility service or a screenshot.
- **Auto context** — the accessibility service captures the active app's information automatically.

### Review & Sync

After tapping Done:

1. Review captured chunks (stitched or separate view)
2. Delete unwanted chunks
3. Tap **Sync** — the LLM transcribes your handwriting and writes the result to your Obsidian vault
4. Cost estimate shown before sync

## Troubleshooting

<details>
<summary><strong>Floating bubble doesn't appear</strong></summary>

- Go to **Settings > Apps > Synapse > Display over other apps** and make sure it's enabled
- On some devices, battery optimization can kill the overlay service. Exclude Synapse from battery optimization.
- Restart the app after granting the permission

</details>

<details>
<summary><strong>Accessibility service keeps turning off</strong></summary>

- Some Android skins (MIUI, OneUI, ColorOS) aggressively kill accessibility services
- Exclude Synapse from battery optimization
- Lock the app in the recent apps tray (long-press > Lock)
- On MIUI: Settings > Apps > Manage apps > Synapse > Autostart > Enable

</details>

<details>
<summary><strong>Transcription fails or returns errors</strong></summary>

- Verify your API key is correct in Settings
- Check your internet connection (or Ollama server reachability)
- If using rate limiting in **Safe** mode, requests are throttled — switch to **Fast** if you're hitting limits
- Check that the provider's API isn't down (e.g. Google AI Studio status page)

</details>

<details>
<summary><strong>Region capture doesn't work</strong></summary>

- Grant the screen capture permission when prompted (MediaProjection)
- Make sure the accessibility service is enabled
- Region capture requires both permissions to function

</details>

<details>
<summary><strong>Sync to Obsidian vault fails</strong></summary>

- Re-select the vault folder in Settings — SAF URI permissions can expire after app updates
- Make sure the Obsidian vault folder exists on device storage
- Check that Synapse has storage access (Settings > Apps > Synapse > Permissions)

</details>

## FAQ

<details>
<summary><strong>Does Synapse send my data anywhere?</strong></summary>

Only to the LLM provider you configure, and only when you tap Sync. Synapse also sends crash reports (stack traces, device info) to Google via Firebase Crashlytics to help diagnose issues. There is no other analytics, telemetry, or third-party tracking. If you use Ollama, transcription stays on your local network. See [PRIVACY.md](PRIVACY.md) for full details.

</details>

<details>
<summary><strong>Which provider should I use?</strong></summary>

- **Gemini** — best starting point. Free tier available, good handwriting recognition.
- **Claude** — strong at structured output and following formatting instructions.
- **OpenAI** — reliable general-purpose option.
- **Ollama** — fully offline, but requires a local server and recognition quality depends on hardware.

</details>

<details>
<summary><strong>Can I use Synapse without an Obsidian vault?</strong></summary>

Yes. You can sync to any local folder selected via SAF. The Obsidian vault is the primary use case but any folder works.

</details>

<details>
<summary><strong>Can I customize the transcription prompt?</strong></summary>

Yes. Go to **Settings > Edit Prompt**. The editor validates that required placeholders (`{cleanup_enabled}`, `{advanced_formatting}`) are present. You can reset to the default template at any time.

</details>

<details>
<summary><strong>What apps are excluded from the accessibility service?</strong></summary>

Synapse's accessibility service automatically excludes sensitive apps: banking apps (Chase, Bank of America, Wells Fargo, Citi, USAA, Ally), password managers (1Password, LastPass, Bitwarden, Dashlane, KeePass), payment apps (Venmo, Cash App, PayPal), and authenticator apps (Google Authenticator, Authy, FreeOTP).

</details>

<details>
<summary><strong>Does it work offline?</strong></summary>

Capture works fully offline — you can scribble and save chunks without a connection. Transcription requires either an internet connection (for cloud providers) or a local Ollama server. Failed syncs are queued and can be retried manually.

</details>

## Requirements

- Android 8.0+ (API 26)
- Stylus recommended (works with finger too)
- API key for at least one cloud LLM provider, or Ollama running locally

## Permissions

| Permission | Purpose |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Floating overlay bubble and capture canvas |
| `FOREGROUND_SERVICE` | Keep capture service alive during sessions |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Required on Android 14+ for overlay service |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required on Android 14+ for screen capture |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| `INTERNET` | LLM API calls and sync |
| `ACCESS_NETWORK_STATE` | Offline detection for sync queue |
| `VIBRATE` | Haptic feedback on region selection |
| `WAKE_LOCK` | CPU active during background processing |
| Accessibility Service | Text extraction from screen regions, auto-context capture |
| MediaProjection | Screenshot-based region capture (requested on demand) |
| SAF (document access) | Read/write Obsidian vault and local folders |

<details>
<summary><strong>Architecture</strong></summary>

```
app/src/main/java/com/synapse/
├── SynapseApplication.kt          # Application class, Koin initialization
├── api/                            # LLM service layer
│   ├── TranscriptionService.kt     # Interface (transcribe, textQuery, visionQuery)
│   ├── BaseLlmService.kt          # Base class with retry, rate limiting, error handling
│   ├── GeminiService.kt           # Google Gemini implementation
│   ├── ClaudeService.kt           # Anthropic Claude implementation
│   ├── OpenAiService.kt           # OpenAI implementation
│   ├── OllamaService.kt           # Local Ollama implementation
│   ├── LlmProvider.kt            # Provider enum and factory routing
│   ├── QuestionAnswerService.kt   # Q&A via text-based LLM calls
│   ├── PromptTemplate.kt         # Base transcription prompt
│   ├── TranscriptionServiceFactory.kt # Factory for TranscriptionService instances
│   └── TranscriptionModels.kt    # TranscribedNote, ProcessedResult, ChunkData
├── data/
│   ├── LlmSettingsProvider.kt    # Reads LLM config from DataStore + SecureKeyStorage
│   ├── cost/
│   │   └── LlmCostCalculator.kt  # Token pricing per model
│   ├── repository/
│   │   ├── ChunkRepository.kt    # Chunk CRUD and image operations
│   │   ├── SessionRepository.kt  # Session lifecycle management
│   │   ├── ProjectRepository.kt  # Project CRUD
│   │   ├── SyncRepository.kt     # Sync orchestration (segmentation, transcription, file write)
│   │   ├── SessionSegmenter.kt   # Groups chunks/contexts into segments by timeline
│   │   └── SyncPrompts.kt        # Sync-related prompt templates
│   └── storage/
│       ├── ChunkStorage.kt       # WebP image storage with atomic writes
│       ├── SessionStorage.kt     # Session metadata JSON persistence
│       ├── ProjectStorage.kt     # Project configuration persistence
│       ├── SyncStorage.kt        # Persistent sync queue
│       ├── SecureKeyStorage.kt   # AES-256-GCM encrypted API key storage
│       ├── ImageProcessor.kt     # WebP conversion, stitching, thumbnails
│       ├── VaultManager.kt       # Obsidian vault file I/O via SAF
│       ├── StorageHelper.kt      # Common storage utilities
│       └── StorageJson.kt        # JSON serialization models
├── di/
│   └── AppModule.kt              # Koin DI modules (7 module groups)
├── model/
│   ├── CapturedContext.kt        # Sealed class: SelectedText, RegionText, RegionImage, AutoContext
│   ├── Chunk.kt                  # Chunk data model
│   ├── Session.kt                # Session data model
│   ├── Project.kt                # Project data model
│   ├── CostModels.kt            # CostEstimate, UsageStats, TokenPricing
│   ├── LlmConfig.kt             # Multi-provider configuration
│   ├── QueuedSync.kt            # Offline queue item
│   └── SyncStatus.kt            # Sealed class: Idle, Queued, InProgress, Success, PartialSuccess, Error
├── service/
│   ├── OverlayService.kt         # Main floating overlay service (bubble + canvas + region mode)
│   ├── CaptureOverlayManager.kt  # Overlay lifecycle management
│   ├── FloatingBubbleManager.kt  # Draggable bubble widget with chunk badge
│   ├── OverlaySessionManager.kt  # Capture session lifecycle
│   ├── InputDispatcher.kt        # Touch/stylus input dispatch
│   ├── SynapseAccessibilityService.kt # Screen text extraction
│   ├── ScreenshotManager.kt      # MediaProjection screen capture
│   ├── MediaProjectionHolder.kt  # Activity-Service bridge for projection consent
│   ├── NotificationHelper.kt     # Foreground service notifications
│   ├── PermissionHealthMonitor.kt # Permission state monitoring
│   └── SynapseCapabilities.kt    # Device capability detection
├── ui/
│   ├── MainActivity.kt           # Main activity entry point
│   ├── ProcessTextActivity.kt    # Android ACTION_PROCESS_TEXT handler
│   ├── components/
│   │   ├── ContextCard.kt        # Context display cards in review
│   │   ├── CostDisplay.kt        # Cost banner and usage stats
│   │   ├── LlmSettingsSection.kt # Multi-provider settings UI
│   │   └── SyncStatusIndicator.kt # Queue status display
│   ├── navigation/
│   │   ├── MainScreen.kt         # Main navigation host
│   │   └── NavGraph.kt           # Navigation graph definition
│   ├── onboarding/
│   │   ├── OnboardingScreen.kt   # Main onboarding pager
│   │   ├── OnboardingPage.kt     # Individual onboarding page
│   │   ├── OnboardingViewModel.kt # Onboarding logic
│   │   ├── OnboardingState.kt    # Onboarding state management
│   │   ├── AccessibilityPermissionScreen.kt # Accessibility permission request
│   │   └── DestinationSetupScreen.kt # Destination configuration
│   ├── overlay/
│   │   ├── CaptureCanvas.kt      # Drawing surface + region gesture detection
│   │   ├── CaptureViewModel.kt   # Capture overlay ViewModel
│   │   ├── RegionGestureDetector.kt # Hold-drag gesture recognizer
│   │   ├── StrokeManager.kt      # Stroke data management
│   │   ├── PalmRejectionFilter.kt # Palm rejection for stylus
│   │   └── StrokeSmoother.kt     # Catmull-Rom stroke smoothing
│   ├── review/
│   │   ├── ReviewScreen.kt       # Session list and sync management
│   │   ├── ReviewViewModel.kt    # Review screen state
│   │   ├── SessionCard.kt        # Session card component
│   │   ├── ChunkThumbnail.kt     # Chunk thumbnail display
│   │   └── SyncStatusBar.kt      # Sync progress indicator
│   ├── settings/
│   │   ├── SettingsScreen.kt     # Main settings UI
│   │   ├── SettingsViewModel.kt  # Settings state
│   │   ├── ProjectManagerScreen.kt # Project CRUD UI
│   │   ├── PromptEditorScreen.kt # Custom prompt editor
│   │   ├── AccessibilitySettingsActivity.kt # Accessibility service settings
│   │   └── components/
│   │       ├── SettingsDropdown.kt
│   │       ├── SettingsSlider.kt
│   │       ├── SettingsTextField.kt
│   │       └── SettingsToggle.kt
│   └── theme/
│       ├── Theme.kt              # Material 3 theme
│       └── Typography.kt         # Typography definitions
└── util/
    ├── ApiKeyValidator.kt        # API key format validation
    ├── CrashReporter.kt          # Firebase Crashlytics wrapper
    ├── HapticFeedbackHelper.kt   # Haptic feedback on region selection
    ├── NetworkMonitor.kt         # Connectivity state tracking
    ├── OutputSanitizer.kt        # LLM output sanitization
    └── PermissionHelper.kt       # Runtime permission utilities
```

</details>

<details>
<summary><strong>Tech Stack</strong></summary>

| Component | Technology |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose + Material 3 |
| DI | Koin 3.5.6 |
| State | ViewModel + StateFlow |
| Storage | DataStore, SAF, WebP |
| Networking | OkHttp 4.12.0 |
| Images | Coil 2.5.0 |
| Serialization | Kotlinx Serialization JSON 1.6.3 |
| Security | AndroidX Security Crypto (EncryptedSharedPreferences) |
| Crash Reporting | Firebase Crashlytics (BOM 33.7.0) |
| Min SDK | API 26 (Android 8.0) |
| Target SDK | API 35 (Android 15) |
| JVM | Java 17 |
| Build | AGP 9.0, Gradle Kotlin DSL |

</details>

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full guide. In short:

1. Check existing issues first
2. Open an issue to discuss major changes
3. Fork, branch, PR
4. Test on a real device (overlay and accessibility features need physical hardware)

## Privacy

Synapse is local-first. Your data stays on your device unless you explicitly sync it to an LLM provider. See [PRIVACY.md](PRIVACY.md) for details on what is accessed and why.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for version history and notable changes.

## License

GPLv3 License — see [LICENSE](LICENSE).
