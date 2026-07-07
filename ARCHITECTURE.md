# Architecture

This document maps the Synapse codebase for maintainers. It describes the package
layout, the capture → storage → review → sync data flow, the Koin DI structure,
the largest/most load-bearing files, and the platform-specific pieces (overlay
windows, accessibility, foreground services, MediaProjection).

Everything here was verified against the code in `app/src/main/java/com/synapse/`
at the time of writing. If something looks off, check the source before trusting
this file — see the note on doc drift in `MAINTAINERS.md`.

## Package layout

```
app/src/main/java/com/synapse/
├── SynapseApplication.kt   Application class: Koin init, notification channels
├── api/                    LLM provider clients (Gemini, Claude, OpenAI, Ollama)
├── data/
│   ├── LlmSettingsProvider.kt
│   ├── cost/                Token pricing / cost estimation
│   ├── repository/          Business logic layer (sessions, chunks, projects, sync)
│   └── storage/              File/DataStore persistence (chunks, sessions, vault, keys)
├── di/                      Koin module definitions (AppModule.kt)
├── model/                   Plain data classes and sealed classes
├── service/                 Android Service/AccessibilityService classes + helpers
├── ui/                      Jetpack Compose screens, ViewModels, components
└── util/                    Small stateless helpers
```

There is also an empty `data/preferences/` package (only a `.gitkeep`) — reserved,
currently unused.

## Data flow: capture → storage → review → sync

1. **Capture.** `OverlayService` (a foreground `Service`) hosts a
   `AbstractComposeView`-based overlay window. `FloatingBubbleManager` draws the
   draggable trigger bubble; tapping it opens `CaptureOverlayManager`'s fullscreen
   transparent canvas (`ui/overlay/CaptureCanvas.kt`). Strokes are captured via
   `InputDispatcher` and buffered by `StrokeManager`/`PalmRejectionFilter`/
   `StrokeSmoother`. `CaptureViewModel` owns the per-chunk timeout logic: after
   the configured inactivity timeout (default 1s), it fades the canvas, emits a
   `CaptureEvent.ChunkCaptured` with a rendered `Bitmap`, and clears for the next
   chunk.
2. **Persist chunk.** `OverlayService` forwards each captured chunk to
   `OverlaySessionManager.saveChunk()`, which lazily creates a `Session` via
   `SessionRepository` on the first chunk, then persists the bitmap via
   `ChunkRepository` → `ChunkStorage` (WebP file on disk, atomic write). Context
   captures (selected text, region text/image, auto-context) go through the same
   manager into `CapturedContext` entries on the session.
3. **Session end.** Tapping "Done" ends the capture session and navigates to the
   review flow (`ui/review/ReviewScreen.kt` + `ReviewViewModel`), which lists
   sessions from `SessionRepository`/`ChunkRepository`, renders chunk thumbnails,
   and lets the user delete chunks, pick a target project, and see a cost
   estimate (`LlmCostCalculator`) before syncing.
4. **Sync.** Tapping "Sync" calls `SyncRepository.syncSession()`
   (`SyncRepositoryImpl`, `data/repository/SyncRepository.kt`). It:
   - loads the session and validates it has content,
   - resolves the configured `TranscriptionService` via
     `DefaultTranscriptionServiceFactory` (provider chosen in Settings),
   - splits the session into `Segment`s by timestamp via `SessionSegmenter`
     (context-only segments, chunk-only segments, or context+chunk "Q&A" segments),
   - transcribes each segment (image chunks and/or region screenshots go through
     the LLM's vision endpoint; text contexts are formatted or quoted),
   - appends the resulting markdown to the target file in the user's vault via
     `VaultManager` (SAF), and
   - updates `SyncStatus` (`Idle`/`Queued`/`InProgress`/`Success`/`PartialSuccess`/
     `Error`) as a `StateFlow` that `ReviewViewModel` observes.
   Failed syncs are persisted in `SyncStorage`'s queue and retried automatically
   when `NetworkMonitor` reports connectivity restored (`SyncRepositoryImpl`
   observes this and calls `retryFailed()` after a stabilization delay).

## Koin DI structure

DI is defined in a single file, `di/AppModule.kt`, with **four modules**, loaded
in dependency order from `SynapseApplication.initKoin()`:

1. `storageModule` — DataStore, `SecureKeyStorage`, `ChunkStorage`,
   `SessionStorage`, `ProjectStorage`, `SyncStorage`, `VaultManager`,
   `ImageProcessor`. No dependencies on other app modules.
2. `apiModule` — `CertificatePinner` (hardcoded pins for the three cloud LLM
   hosts), the shared `OkHttpClient`, `DefaultTranscriptionServiceFactory`,
   `QuestionAnswerService`, `NetworkMonitor`.
3. `repositoryModule` — `ChunkRepository`, `SessionRepository`,
   `ProjectRepository`, `LlmSettingsProvider`, `SyncRepository` (bound to
   `SyncRepositoryImpl`), `LlmCostCalculator`, `NotificationHelper`,
   `PermissionHelper`, `SynapseCapabilities`, `PermissionHealthMonitor`,
   `ScreenshotManager`.
4. `viewModelModule` — `CaptureViewModel`, `ReviewViewModel`, `SettingsViewModel`,
   `OnboardingViewModel` (Koin `viewModel { }` DSL).

`getAllModules()` returns the four in that order; `SynapseApplication` passes
them to `startKoin { modules(...) }`. `OverlayService` (a `Service`, not a
Compose entry point) pulls its dependencies with Koin's `by inject()` delegate
rather than constructor injection, since Android instantiates services itself.

## Key components

- **`service/OverlayService.kt`** (~950 lines) — the foreground `Service` that
  owns the overlay window lifecycle. Implements `LifecycleOwner` and
  `SavedStateRegistryOwner` so it can host Compose content
  (`AbstractComposeView`) outside of an Activity. Delegates to
  `FloatingBubbleManager` (bubble UI), `CaptureOverlayManager` (fullscreen
  canvas), and `OverlaySessionManager` (session/chunk persistence). Handles
  `onStartCommand` action routing, foreground service type selection
  (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, and
  `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` when a screenshot is requested),
  and guards against double cleanup on `onTaskRemoved` → `onDestroy`.
- **`data/storage/ChunkStorage.kt`** (~800 lines) — file-backed WebP storage for
  captured stroke images. Writes are atomic (temp file, verify, rename). Keeps a
  bounded per-session `Mutex` map (pruned above 100 entries) to serialize writes
  within a session without a single global lock. All public operations run
  under `withContext(Dispatchers.IO)`. Also does storage pre-flight checks
  (50MB free minimum), corruption marking, and thumbnail generation.
- **`data/repository/SyncRepository.kt`** (~700 lines) — orchestrates the sync
  pipeline described above. `SyncRepositoryImpl` is `Closeable` and owns a
  `CoroutineScope(SupervisorJob() + Dispatchers.IO)` used both for the sync work
  itself and for a background network-observer coroutine that retries failed
  syncs on reconnect.
- **`api/BaseLlmService.kt`** (~660 lines) — abstract base class shared by all
  four provider implementations (`GeminiService`, `ClaudeService`,
  `OpenAiService`, `OllamaService`). Owns request retry with exponential
  backoff + jitter (`executeWithRetry`), a sliding-window rate limiter
  (`RateLimitState`/`RateLimitConfig` in `api/TranscriptionModels.kt`, tracking
  request timestamps over a rolling 60-second window; only enforced when
  "Safe" rate limiting is selected), HTTP error classification into
  `TranscriptionError` subtypes, and a 5MB response body cap. Subclasses only
  implement URL/auth/request-body/response-parsing hooks.
- **`ui/review/ReviewScreen.kt`** (~730 lines) — the largest Compose screen.
  Renders the session list (stitched or separate chunk view), sync status,
  cost banner, and delete/sync actions, driven by `ReviewViewModel`.

## Threading conventions

- **Storage layer** (`data/storage/*`): every public suspend function wraps its
  body in `withContext(Dispatchers.IO)` explicitly (see `ChunkStorage`,
  consistent across the other storage classes).
- **Repository layer** (`data/repository/*`): repositories that do background
  work own a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` (e.g.
  `SyncRepositoryImpl`) rather than relying solely on caller context; long-lived
  repositories are `Closeable` and cancel this scope on close.
- **API layer** (`api/*`): `BaseLlmService`'s HTTP calls (`httpClient.newCall(...).execute()`)
  are blocking calls made from plain (non-`withContext`) code, executed inside
  suspend functions — they rely on the caller already running on
  `Dispatchers.IO` (which is true given the repository layer's scope).
- **UI layer** (`ui/*`): ViewModels (`CaptureViewModel`, `ReviewViewModel`,
  `SettingsViewModel`, `OnboardingViewModel`) use `viewModelScope` and expose
  state via `StateFlow`/`MutableStateFlow`, following standard Compose/AAC
  conventions.
- **`OverlayService`** runs its own `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`
  since it drives UI (the overlay window) directly from a `Service`, and
  cancels it in `onDestroy()`.

## Platform-specific pieces

These are the parts of the codebase that only make sense — and can only be
tested — on a real device (see `MAINTAINERS.md` for the testing implication).

- **Overlay windows** — `service/OverlayService.kt`,
  `service/FloatingBubbleManager.kt`, `service/CaptureOverlayManager.kt`. Use
  `WindowManager` + `SYSTEM_ALERT_WINDOW` to draw the floating bubble and the
  fullscreen capture canvas over other apps.
- **Accessibility service** — `service/SynapseAccessibilityService.kt`. Reads
  on-screen text for context capture and region-text extraction; maintains a
  hardcoded exclusion list for sensitive apps (banking, password managers,
  payment, authenticator apps) and caps node traversal (`MAX_NODE_DEPTH`,
  `MAX_CACHED_NODES`, `MAX_COMBINED_TEXT_LENGTH`) to bound cost on deep view
  hierarchies. Exposes a static `getInstance()`/`isEnabled()` pair used by the
  rest of the app to check/reach the running service.
- **Foreground services** — `OverlayService` is the main one; it selects its
  foreground service type dynamically (`SPECIAL_USE` normally,
  `MEDIA_PROJECTION or SPECIAL_USE` once a screen-capture session starts),
  which matters for Android 14+ foreground service type enforcement.
- **MediaProjection** — `service/ScreenshotManager.kt` performs the actual
  screenshot capture; `service/MediaProjectionHolder.kt` is a process-wide
  singleton that bridges the Activity-scoped permission result (which can only
  be obtained via `Activity.startActivityForResult`) to `OverlayService`, with
  a 30-minute TTL after which re-consent is required.

## Doc-drift note

The README's collapsible "Architecture" and "Tech Stack" sections contain a
similarly detailed file tree and dependency table. This document was written
independently from the source and cross-checked against that section; no
material discrepancies were found in the package layout. See `MAINTAINERS.md`
for a discrepancy that *was* found (test file count) and other doc-vs-code
notes.
