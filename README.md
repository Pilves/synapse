# Synapse

**Zero-friction handwriting capture for Obsidian.**

Synapse is an Android overlay app that lets you capture handwritten notes without leaving your current app. Scribble quick thoughts, and later sync them to your Obsidian vault as clean, formatted markdown — transcribed by your choice of LLM.

## Features

- **Floating overlay** — capture notes over any app without switching
- **Automatic chunking** — configurable timeout (1–10s) creates natural breaks between strokes
- **Smart transcription** — messy handwriting → clean markdown via LLM
- **Mermaid diagrams** — hand-drawn flowcharts converted to Mermaid code (advanced formatting mode)
- **Intent detection** — auto-classifies notes as plain notes, tasks, questions, or reminders
- **Question answering** — detected questions are answered inline by the LLM
- **Context capture** — select text, capture screen regions, or auto-capture active app info
- **Multiple destinations** — sync to Obsidian vault, local folder, clipboard, or share sheet
- **Multi-provider LLM** — Gemini, Claude, OpenAI, or local Ollama
- **Separate providers** — configure different LLMs for transcription (vision) vs. question answering (text)
- **Cost tracking** — estimated cost per sync, cumulative usage stats
- **Offline queue** — failed syncs are queued with retry controls
- **Prompt customization** — edit the transcription prompt template in-app
- **Undo support** — undo last stroke while capturing

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

You can configure **separate providers** for transcription (image-based) and question answering (text-based) in Settings → LLM Configuration.

<details>
<summary><strong>Gemini setup</strong></summary>

1. Go to [Google AI Studio](https://aistudio.google.com/apikey) and create an API key
2. In Synapse, open **Settings → LLM Configuration**
3. Select **Google Gemini** as your provider
4. Paste your API key
5. Tap **Save**

</details>

<details>
<summary><strong>Claude setup</strong></summary>

1. Go to [Anthropic Console](https://console.anthropic.com/) and create an API key
2. In Synapse, open **Settings → LLM Configuration**
3. Select **Anthropic Claude** as your provider
4. Paste your API key
5. Tap **Save**

</details>

<details>
<summary><strong>OpenAI setup</strong></summary>

1. Go to [OpenAI Platform](https://platform.openai.com/api-keys) and create an API key
2. In Synapse, open **Settings → LLM Configuration**
3. Select **OpenAI** as your provider
4. Paste your API key
5. Tap **Save**

</details>

<details>
<summary><strong>Ollama setup (local/offline)</strong></summary>

1. Install [Ollama](https://ollama.ai/) on a machine on your local network
2. Pull the LLaVA model: `ollama pull llava`
3. Start the Ollama server (default port 11434)
4. In Synapse, open **Settings → LLM Configuration**
5. Select **Ollama (Local)** as your provider
6. Enter the server URL (e.g. `http://192.168.1.100:11434`)
7. No API key needed

</details>

### Destinations

| Destination | Description |
|---|---|
| **Clipboard** | Copies transcribed markdown to clipboard |
| **Local Folder** | Appends to a file in a user-selected folder (e.g. Obsidian vault) |
| **Share to…** | Opens Android share sheet; sends markdown to compatible apps (Obsidian, Notion, Logseq, Discord, Slack) and plain text to others |

Choose default destinations during onboarding, or change per-session in the review screen.

### Capture Settings

| Setting | Default | Range |
|---|---|---|
| Chunk timeout | 1 second | 1–10 seconds |
| Session auto-end | 15 minutes | 5–60 minutes |
| Fade animation | 0.3 seconds | 0–1 second |
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
3. Choose destination(s) — Clipboard, Local Folder, Share
4. Tap **Sync** — the LLM transcribes your handwriting and sends the result to your chosen destinations
5. Cost estimate shown before sync

## Troubleshooting

<details>
<summary><strong>Floating bubble doesn't appear</strong></summary>

- Go to **Settings → Apps → Synapse → Display over other apps** and make sure it's enabled
- On some devices, battery optimization can kill the overlay service. Exclude Synapse from battery optimization.
- Restart the app after granting the permission

</details>

<details>
<summary><strong>Accessibility service keeps turning off</strong></summary>

- Some Android skins (MIUI, OneUI, ColorOS) aggressively kill accessibility services
- Exclude Synapse from battery optimization
- Lock the app in the recent apps tray (long-press → Lock)
- On MIUI: Settings → Apps → Manage apps → Synapse → Autostart → Enable

</details>

<details>
<summary><strong>Transcription fails or returns errors</strong></summary>

- Verify your API key is correct in Settings → LLM Configuration
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
- Check that Synapse has storage access (Settings → Apps → Synapse → Permissions)

</details>

## FAQ

<details>
<summary><strong>Does Synapse send my data anywhere?</strong></summary>

Only to the LLM provider you configure, and only when you tap Sync. No analytics, no telemetry, no third-party tracking. If you use Ollama, everything stays on your local network. See [PRIVACY.md](PRIVACY.md).

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

Yes. You can sync to any local folder, copy to clipboard, or share to any app. The Obsidian vault is optional.

</details>

<details>
<summary><strong>Can I customize the transcription prompt?</strong></summary>

Yes. Go to **Settings → Edit Prompt**. The editor validates that required placeholders (`{cleanup_enabled}`, `{advanced_formatting}`) are present. You can reset to the default template at any time.

</details>

<details>
<summary><strong>What apps are excluded from the accessibility service?</strong></summary>

Synapse's accessibility service automatically excludes sensitive apps: banking apps (Chase, Bank of America, Wells Fargo, Citi, USAA, Ally), password managers (1Password, LastPass, Bitwarden, Dashlane, KeePass), payment apps (Venmo, Cash App, PayPal), and authenticator apps (Google Authenticator, Authy, FreeOTP).

</details>

<details>
<summary><strong>Does it work offline?</strong></summary>

Capture works fully offline — you can scribble and save chunks without a connection. Transcription requires either an internet connection (for cloud providers) or a local Ollama server. Failed syncs are queued and retried automatically when connectivity returns.

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
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| `INTERNET` | LLM API calls and sync |
| `ACCESS_NETWORK_STATE` | Offline detection for sync queue |
| `VIBRATE` | Haptic feedback on region selection |
| Accessibility Service | Text extraction from screen regions, auto-context capture |
| MediaProjection | Screenshot-based region capture (requested on demand) |
| SAF (document access) | Read/write Obsidian vault and local folders |

<details>
<summary><strong>Architecture</strong></summary>

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

</details>

<details>
<summary><strong>Tech Stack</strong></summary>

| Component | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| DI | Koin |
| State | ViewModel + StateFlow |
| Storage | DataStore, SAF, WebP |
| Networking | OkHttp |
| Images | Coil |
| Min SDK | API 26 (Android 8.0) |
| Target SDK | API 35 (Android 15) |
| JVM | Java 17 |

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

GPL License — see [LICENSE](LICENSE).
