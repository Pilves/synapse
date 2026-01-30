# CLAUDE.md — Synapse Project Guide

## What is Synapse?

Android overlay app (API 26+) for zero-friction handwriting capture. Users draw on a floating transparent canvas without leaving their current app. Strokes are batched into "chunks," sent to an LLM for transcription, and the resulting markdown is synced to Obsidian vaults, local folders, clipboard, or share sheets.

## Tech Stack

- **Language:** Kotlin 2.2.10, JVM 17
- **Build:** Gradle with Kotlin DSL, AGP 9.0, version catalog at `gradle/libs.versions.toml`
- **UI:** Jetpack Compose + Material 3 (BOM 2024.06.00)
- **DI:** Koin 3.5.6 — modules defined in `app/src/main/java/com/synapse/di/AppModule.kt`
- **Networking:** OkHttp 4.12.0 with certificate pinning
- **Serialization:** Kotlinx Serialization JSON 1.6.3
- **State:** ViewModel + StateFlow (no LiveData)
- **Storage:** DataStore Preferences, EncryptedSharedPreferences for API keys
- **File access:** SAF (Storage Access Framework) via AndroidX DocumentFile
- **Image:** Coil 2.5.0, WebP format for chunk images
- **Crash reporting:** Firebase Crashlytics
- **Navigation:** Jetpack Navigation Compose 2.7.7
- **Testing:** JUnit 4, Robolectric, MockK, Turbine, Coroutine Test

## Build & Test

```bash
# Build debug APK (Windows)
gradlew.bat assembleDebug

# Build debug APK (Linux/macOS)
./gradlew assembleDebug

# Run unit tests
gradlew.bat test          # Windows
./gradlew test            # Linux/macOS

# Run lint
gradlew.bat lint          # Windows
./gradlew lint            # Linux/macOS
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

Overlay, accessibility, and stylus features require a **physical device** — they don't work on emulators.

## Architecture

**Clean Architecture + MVVM** with these layers:

```
api/           → LLM provider implementations (Gemini, Claude, OpenAI, Ollama)
data/
  cost/        → Token pricing & usage tracking
  preferences/ → DataStore preferences
  repository/  → Business logic (Chunk, Session, Project, Sync repos)
  storage/     → File I/O, image processing, vault management
di/            → Koin DI modules (7 modules, loaded in explicit order)
model/         → Data classes & sealed classes
service/       → Android services (overlay, capture, accessibility) + helpers
ui/            → Compose screens, ViewModels, components
util/          → Permission helpers, networking, validation
```

### Key patterns

- **Repository pattern** for all business logic (`ChunkRepository`, `SessionRepository`, `ProjectRepository`, `SyncRepository`)
- **Factory pattern** for LLM provider routing (`DefaultTranscriptionServiceFactory`, `LlmProviderFactory`)
- **Sealed classes** for type-safe variants (`CapturedContext`, `SyncResult`, `TranscriptionError`)
- **StateFlow** for all ViewModel state; `SharedFlow` for one-shot events
- **Atomic file writes** with RIFF/WEBP header verification for chunk images
- **Exponential backoff** retry logic for LLM API calls (3 retries)

## LLM Providers

| Provider | Model | Rate Limit |
|----------|-------|------------|
| Google Gemini | `gemini-2.0-flash` | 15 RPM |
| Anthropic Claude | `claude-3-5-haiku-20241022` | 50 RPM |
| OpenAI | `gpt-4o-mini` | 500 RPM |
| Ollama (local) | `llava` | Unlimited |

Each provider implements `TranscriptionService`. Common logic lives in `BaseLlmService`.

## Key Services

- **`OverlayService`** — Floating bubble + transparent capture canvas (Compose UI host)
- **`CaptureService`** — Processes strokes into chunks, manages sessions
- **`SynapseAccessibilityService`** — Extracts on-screen text for context, filters sensitive apps
- **`ProcessTextActivity`** — Handles `ACTION_PROCESS_TEXT` for text selection context

## Security

- Certificate pinning on all LLM API calls (leaf + intermediate CA)
- AES-256-GCM encrypted API key storage
- HTTPS-only network config (localhost exception for Ollama)
- Path traversal prevention on vault file writes
- Sensitive app exclusion list (banking, password managers)
- 5MB response body size limit
- 30-minute TTL on MediaProjection tokens

## Permissions

The app requests: `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE`, `VIBRATE`, `WAKE_LOCK`, plus Accessibility Service and MediaProjection at runtime.

## Testing

10 unit test files in `app/src/test/java/com/synapse/` covering:
- HTTP error handling, prompt template validation, JSON response parsing
- Cost calculation, repository logic
- ViewModel state, onboarding state

Manual testing on a physical device is required for overlay, accessibility, and stylus input.

## Partially Implemented / Future Work

- **Offline sync auto-retry:** Queue (`SyncStorage`) exists but background retry not wired

## Conventions

- Kotlin official code style (`kotlin.code.style=official`)
- ViewModels suffixed with `ViewModel`, services with `Service`
- Sealed classes for type-safe enums with data
- Koin `single` for storage/API/repository singletons; `viewModel` DSL for ViewModels
- No LiveData — StateFlow everywhere
- Prefer small focused composables
- Structured concurrency with `SupervisorJob` for error isolation

## CI/CD

GitHub Actions (`.github/workflows/android.yml`) — manual trigger, runs tests + lint + builds debug APK + creates GitHub release. Requires `GOOGLE_SERVICES_JSON` secret (base64-encoded).
