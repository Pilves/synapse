# Synapse — Implemented Features

Current state of every feature in the codebase. Each item is marked:
- **Implemented** — fully functional
- **Partial** — code exists but not wired into the main flow

---

## Capture System — Implemented

**Floating overlay**
- Draggable bubble with pending-chunk count badge (`FloatingBubbleManager`)
- Drag to bottom 15% of screen to dismiss
- Tap to open fullscreen transparent canvas
- Overlay lifecycle managed by `CaptureOverlayManager`

**Toolbar** (during capture)
- Minimize, Undo, Region mode toggle, Done, Discard — all functional

**Canvas drawing**
- Smooth quadratic bezier paths
- White strokes with dark outline (`#282828`)
- Three input modes: stylus-write/finger-scroll, both-write, stylus-only
- Region selection overlay (hold-and-drag with dashed border, corner handles, haptic feedback via `HapticFeedbackHelper`)

**Chunk capture**
- Configurable inactivity timeout (default 1s, range 1-10s)
- Fade animation after chunk creation (200ms)
- Session auto-end after 15 minutes of inactivity (configurable 5-60min)
- Undo last stroke

**`PalmRejectionFilter`** — Tablet palm rejection for stylus input; detects palm via contact area geometry with configurable thresholds.

**Stroke management**
- Thread-safe stroke accumulation
- `toBitmapForOcr()` — crops to bounds, scales to max 800px, white background, 20px padding
- `toBitmap()` — full render with outline effect

---

## LLM Transcription — Implemented

All four providers fully implement `transcribe()`, `textQuery()`, and `visionQuery()`:

| Provider | Model | Rate Limits | Notes |
|----------|-------|-------------|-------|
| Gemini | `gemini-2.0-flash` | 15 RPM (code-enforced), ~1500/day (provider limit) | Free tier available |
| Claude | `claude-3-5-haiku-20241022` | 50 RPM (code-enforced), ~10000/day (provider limit) | |
| OpenAI | `gpt-4o-mini` | 500 RPM (code-enforced), ~10000/day (provider limit) | Requests JSON response format |
| Ollama | `llava` (local) | Unlimited | Health check pings `/api/tags` |

> **Note:** Daily rate limits shown above are imposed by the API provider, not enforced in application code. Only RPM limits are enforced client-side.

**Retry logic** — exponential backoff, 3 retries, handles 429/5xx errors per provider. Implemented in `BaseLlmService`.

**Rate limiting** — two modes: Safe (pre-check + backoff for free tiers) and Fast (send immediately, handle 429).

**Service factory** — `DefaultTranscriptionServiceFactory` caches service instances per provider, supports cache invalidation and API key updates.

**`LlmProvider`** — Supports separate transcription/answering providers with flexible name resolution.

**Prompt template** — Three-phase process: Transcribe, Group, Format. Supports cleanup mode and advanced formatting (Mermaid diagrams, LaTeX equations, markdown tables). User-editable via settings.

**JSON parser** — extracts `notes` array from LLM response, maps chunk indices, handles Ollama boundary extraction. Falls back to failure list on parse errors.

---

## Sync System — Implemented

**Session segmentation** — `SessionSegmenter` groups chunks and contexts into segments by timestamp:
- Context-only segments: rendered as markdown blockquotes (or routed through LLM formatting)
- Chunk-only segments: transcribed via LLM in batches of up to 10
- Context + Chunk segments: Q&A flow via `QuestionAnswerService`

**Question answering** — loads context images from disk, combines with chunk images, sends to LLM vision query. Validates image paths against directory traversal. System prompt instructs LLM to reproduce code from images as text blocks.

**File writing** — appends markdown to project files via SAF `DocumentFile` API. Timestamped headers (`## Notes - YYYY-MM-DD HH:mm`) with `---` separators between segments.

**`OutputSanitizer`** — Strips YAML frontmatter, script tags, HTML, Dataview blocks, Templater expressions, and inline JS before vault writes.

**Markdown formatting polish pass** — Sends assembled content through LLM for final markdown cleanup.

**RegionImage vision transcription** — Context-only segments with `RegionImage` sent through vision LLM instead of blockquoted.

**Progress reporting** — granular 0.1 to 1.0 progress during sync.

**Status tracking** — `SyncStatus` sealed class: Idle, Queued, InProgress, Success, PartialSuccess, Error.

---

## Storage — Implemented

**Chunk images** (`cache/chunks/{sessionId}/`)
- WebP format, 85% quality
- Atomic writes: `.tmp` file, RIFF/WEBP header verification, rename (copy+delete fallback)
- Thumbnails: 150px, 70% quality
- Corruption detection: `.corrupted` marker file, excluded from LLM requests
- Storage validation: requires 50MB free space
- Mutex per session for thread safety

**Session metadata** (`files/sessions/{sessionId}.json`)
- `SessionDto` with kotlinx.serialization: id, timestamps, chunks list, contexts list
- Reactive `StateFlow` via `observeSessions()`
- Atomic writes with temp file pattern
- Sessions sorted by `startedAt` descending

**Project configuration** (`files/projects/`)
- Project CRUD with name, folder URI, default file, last used file
- Vault management via SAF `DocumentFile`

**Sync queue** (`files/sync/queue.json`)
- Persistent JSON queue with PENDING, IN_PROGRESS, FAILED states
- Atomic writes, duplicate prevention
- Reset failed items for retry

**`SecureKeyStorage`** — AES256-GCM encrypted API key storage via `EncryptedSharedPreferences` with `MasterKey`; includes migration from plain DataStore.

**`ImageProcessor`** — WebP conversion, thumbnail generation, WebP header validation, chunk stitching (4096px cap, OOM protection), grayscale conversion.

**Settings** (DataStore preferences)
- All capture, LLM, review, and vault settings persisted as key-value pairs
- Reactive `StateFlow` per setting
- Legacy key fallback for migration

---

## Context Capture — Implemented

**Accessibility service**
- Listens for text selection, window state changes, content changes
- Browser URL extraction (Chrome, Firefox, Brave, Edge, Opera)
- Page title capture from window `contentDescription`
- Region text extraction: finds accessibility nodes within screen bounds, sorts by position
- Multi-window support: scans all windows except overlay
- Node caching for performance

**Region capture**
- Hold-and-drag gesture detection with haptic feedback (`HapticFeedbackHelper`)
- Text extraction via accessibility service first
- Screenshot fallback via MediaProjection (`ScreenshotManager`)
- Region images saved as WebP in app cache

**Process text intent**
- `ProcessTextActivity` handles `ACTION_PROCESS_TEXT` from other apps
- Stores selected text for overlay to consume
- Auto-creates session with captured context

**MediaProjection**
- Permission flow via `ActivityResultContracts`
- Result cached in `MediaProjectionHolder` for restore after service restart
- Invalidation on failure with re-request

---

## Review Screen — Implemented

- Displays pending sessions (ended, not yet synced)
- Two view modes: Stitched (combined image) and Separate (thumbnail grid with checkboxes)
- Project dropdown with reactive project list
- Filename input with last-used prefill
- Sync button triggers `SyncRepository.syncSession()`
- Chunk selection for selective sync
- Delete session and individual chunk with confirmation dialogs
- Context display for all pending sessions
- Cost estimate display before sync
- Sync progress and status indicators (`SyncStatusBar`)

---

## Settings — Implemented

All settings are persisted to DataStore and exposed as reactive `StateFlow`:

| Setting | Default | Range |
|---------|---------|-------|
| Chunk timeout | 1s | 1-10s |
| Fade animation | 0.2s | 0-1s |
| Session auto-end | 15 min | 5-60 min |
| Input mode | Stylus write / finger scroll | 3 modes |
| Default view | Stitched | Stitched / Separate |
| LLM provider | Gemini | Gemini / Claude / OpenAI / Ollama |
| API key | — | Text |
| Cleanup mode | Off | On / Off |
| Advanced formatting | Off | On / Off |
| Rate limiting | Safe | Safe / Fast |
| Custom prompt | Default template | Text editor with validation |
| Vault location | — | Folder picker |

Project management screen for adding/editing/deleting projects.
Prompt editor screen with template validation.
Accessibility permission link.

---

## Onboarding — Implemented

Multi-page first-run flow:
1. Welcome screen
2. Overlay permission request
3. Accessibility permission with link to system settings
4. Screen capture permission (MediaProjection)
5. Vault folder selection via SAF picker
6. Destination setup (default sync destinations)
7. API key input with provider-specific validation

Completion flag persisted to DataStore.

Checks resume on `ON_RESUME` to detect permission grants while in system settings.

---

## Navigation — Implemented

All screens routed via Jetpack Navigation Compose:
- Onboarding (first run)
- Review (session list, sync)
- Settings (all options)
- Prompt Editor
- Project Manager
- Accessibility Permission
- Destination Setup

Bottom bar navigation with Review and Settings tabs. FAB starts overlay service.

---

## Cost Tracking — Implemented

**Cost calculator** — pricing table for all models. Estimates input tokens from image size (85 tokens/KB) and context length. Estimates output tokens per chunk (150). Free model detection for Gemini and Ollama.

**Cost display** — banner on review screen shows estimated cost before sync.

---

## Infrastructure — Implemented

**`PermissionHealthMonitor`** — Reactive permission monitoring (HEALTHY/DEGRADED/CRITICAL states) via broadcast receiver.

**`SynapseCapabilities`** — Capability detection (FULL/BASIC/MINIMAL) based on permission state.

**`CrashReporter`** — Firebase Crashlytics integration with non-fatal exception logging.

**`NetworkMonitor`** — Reactive network connectivity monitoring via `ConnectivityManager` callbacks (not yet wired to sync retry).

**`LlmSettingsProvider`** — Reads LLM config from DataStore + `SecureKeyStorage`; supports separate transcription/answering providers.

**`HapticFeedbackHelper`** — Provides haptic feedback for region selection and confirmation dialogs.

---

## Partial / Not Wired

These features have real code but are not connected to the active sync flow:

**Sync queue auto-retry** — `SyncStorage` implements a persistent queue with status tracking. `queueForSync()`, `processQueue()`, and `retryFailed()` all work. Network-triggered automatic retry remains unimplemented — `NetworkMonitor` exists but is not connected to trigger `processQueue()` on connectivity restore.
