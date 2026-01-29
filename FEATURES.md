# Synapse — Implemented Features

Current state of every feature in the codebase. Each item is marked:
- **Implemented** — fully functional
- **Partial** — code exists but not wired into the main flow
- **Stub** — classes/models defined but feature inactive

---

## Capture System — Implemented

**Floating overlay**
- Draggable bubble with pending-chunk count badge
- Drag to bottom 15% of screen to dismiss
- Tap to open fullscreen transparent canvas

**Toolbar** (during capture)
- Minimize, Undo, Region mode toggle, Done, Discard — all functional

**Canvas drawing**
- Smooth quadratic bezier paths
- White strokes with dark outline (`#282828`)
- Three input modes: stylus-write/finger-scroll, both-write, stylus-only
- Region selection overlay (hold-and-drag with dashed border, corner handles, haptic feedback)

**Chunk capture**
- Configurable inactivity timeout (default 1s, range 1-10s)
- Fade animation after chunk creation (200ms)
- Session auto-end after 15 minutes of inactivity (configurable 5-60min)
- Undo last stroke

**Stroke management**
- Thread-safe stroke accumulation
- `toBitmapForOcr()` — crops to bounds, scales to max 800px, white background, 20px padding
- `toBitmap()` — full render with outline effect

---

## LLM Transcription — Implemented

All four providers fully implement `transcribe()`, `textQuery()`, and `visionQuery()`:

| Provider | Model | Rate Limits | Notes |
|----------|-------|-------------|-------|
| Gemini | `gemini-2.0-flash` | 15 RPM, 1500/day | Free tier available |
| Claude | `claude-3-5-haiku-20241022` | 50 RPM, 10000/day | |
| OpenAI | `gpt-4o-mini` | 500 RPM, 10000/day | Requests JSON response format |
| Ollama | `llava` (local) | Unlimited | Health check pings `/api/tags` |

**Retry logic** — exponential backoff, 3 retries, handles 429/5xx errors per provider.

**Rate limiting** — two modes: Safe (pre-check + backoff for free tiers) and Fast (send immediately, handle 429).

**Service factory** — caches service instances per provider, supports cache invalidation and API key updates.

**Prompt template** — V1 prompt active in sync flow. Three-phase process: Transcribe, Group, Format. Supports cleanup mode and advanced formatting (Mermaid diagrams, LaTeX equations, markdown tables). User-editable via settings.

**JSON parser** — extracts `notes` array from LLM response, maps chunk indices, handles Ollama boundary extraction. Falls back to failure list on parse errors.

---

## Sync System — Implemented

**Session segmentation** — `SyncRepository.syncSession()` groups chunks and contexts into segments by timestamp:
- Context-only segments: rendered as markdown blockquotes
- Chunk-only segments: transcribed via LLM in batches of up to 10
- Context + Chunk segments: Q&A flow via `QuestionAnswerService`

**Question answering** — loads context images from disk, combines with chunk images, sends to LLM vision query. Validates image paths against directory traversal. System prompt instructs LLM to reproduce code from images as text blocks.

**File writing** — appends markdown to project files via SAF `DocumentFile` API. Timestamped headers (`## Notes - YYYY-MM-DD HH:mm`) with `---` separators between segments.

**Progress reporting** — granular 0.1 to 1.0 progress during sync.

**Status tracking** — Idle, Queued, InProgress, Success, PartialSuccess, Error.

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
- Hold-and-drag gesture detection with haptic feedback
- Text extraction via accessibility service first
- Screenshot fallback via MediaProjection (`ScreenshotManager`)
- Region images saved as WebP in app cache

**Process text intent**
- `ProcessTextActivity` handles `ACTION_PROCESS_TEXT` from other apps
- Stores selected text in `ContextHolder` for overlay to consume
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
- Sync progress and status indicators

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
4. Vault folder selection via SAF picker
5. API key input with provider-specific validation
6. Completion flag persisted to DataStore

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

## Partial / Not Wired

These features have real code but are not connected to the active sync flow:

**Multi-destination sync** — `ClipboardDestination`, `ShareIntentDestination`, `LocalFolderDestination`, and `DestinationRepository` are implemented. Destination selection UI exists. However, `SyncRepository.syncSession()` writes only to the selected project file. The destination abstraction is not invoked during sync.

**Sync queue processing** — `SyncStorage` implements a persistent queue with status tracking. Queue operations (add, mark in-progress, mark failed, retry) are functional. However, automatic background queue processing and network-triggered retry are not wired. The queue is used for status display only.

**Usage tracking** — `UsageTracker` with DataStore persistence exists. Monthly reset logic implemented. Not called from the sync flow — cost is estimated but not recorded after sync completes.

---

## Stub / Inactive

These are defined as code but the feature is not activated:

**Intent detection** — `IntentType` enum (NOTE, TASK, QUESTION, REMINDER, REACTION), `DetectedIntent`, and `IntentData` sealed classes are defined. `PromptTemplateV2` includes intent detection phases. `IntentDialogs` has confirmation UI for tasks, Q&A, and reminders. `ReminderManager` can create alarms and calendar events. None of this is invoked — the sync flow uses `PromptTemplate` (V1) which does not detect intents.

**Notion / Google Docs destinations** — referenced in the original design spec only. No implementation exists.
