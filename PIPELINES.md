# Synapse — Internal Data Pipelines

**Last updated:** January 2026

This document describes every internal data flow pipeline in Synapse: how data originates, transforms, and reaches its destination.

---

## Architecture Overview

Synapse follows a layered architecture:

```
UI (Jetpack Compose) → ViewModel (StateFlow) → Repository → Storage / Network
```

Dependency injection is handled by Koin, wiring everything together across seven module groups: `appModule`, `storageModule`, `apiModule`, `repositoryModule`, `v2Module`, `serviceHelpersModule`, and `viewModelModule`.

---

## Pipeline 1: Handwriting Capture & Chunk Creation

**Origin:** Stylus/finger input on the canvas overlay

```
Touch Input → CaptureCanvas → StrokeManager (accumulates points)
  → Inactivity timeout (configurable, default 1s)
  → StrokeManager.toBitmapForOcr() renders strokes to bitmap
  → ChunkStorage.saveChunk() writes WebP (quality 85%)
      ├─ Atomic write: .tmp → verify RIFF/WEBP header → rename to .webp
      ├─ Location: cache/chunks/{sessionId}/session_{sessionId}_chunk_{index}.webp
      └─ Thumbnail: _thumb.webp (quality 70%, 150px)
  → SessionStorage appends chunk metadata to session JSON
  → CaptureViewModel emits ChunkCaptured event
  → OverlayService → OverlaySessionManager.saveChunk()
  → ChunkRepository.saveChunk() → ChunkStorage (disk write) + SessionStorage (metadata)
  → fade animation (200ms)
```

**End state:** WebP image on disk + metadata in session JSON.

---

## Pipeline 2: Session Lifecycle

**Origin:** Capture activity lifecycle events

```
startSession()
  → sessionId = UUID.randomUUID().toString()
  → SessionStorage.createSession() writes files/sessions/{sessionId}.json
  → Starts 1-second interval timer for duration tracking

Active session → chunks appended via Pipeline 1

endSession()
  → Sets endedAt timestamp
  → Captures any remaining strokes
  → Persists updated session JSON
  → Session status: Active (endedAt=null) → Pending (endedAt set) → Deleted
```

**Storage format:** `SessionDto` serialized as JSON containing id, timestamps, chunks list, and contexts list.

---

## Pipeline 3: Context Capture

**Origin:** User captures screen content alongside handwriting

Four context types flow through this pipeline:

| Type | Source | Key Fields |
|------|--------|------------|
| `SelectedText` | Text selection via accessibility | text, sourceApp, sourceUrl |
| `RegionText` | OCR of screen region | text, bounds |
| `RegionImage` | Screenshot of screen region | imagePath, bounds, description |
| `AutoContext` | Automatic metadata | sourceApp, sourceUrl, pageTitle |

```
User gesture → RegionCaptureManager extracts region
  → Context object created with timestamp
  → SessionRepository.addContext() appends to session
  → SessionStorage persists updated JSON
  → ReviewViewModel displays contexts
```

Region images stored at: `files/sessions/{sessionId}/region_{id}.webp`

---

## Pipeline 4: Sync Workflow (Transcription & File Write)

**Origin:** User taps "Sync" in ReviewScreen. This is the most complex pipeline.

### Phase 1: Validation
```
ReviewViewModel.syncAll()
  → SyncRepository.syncSession()
  → Validate session + project exist
  → Read LLM settings from DataStore
```

### Phase 2: Segmentation

`SyncRepository.segmentSession()` groups chunks and contexts by timeline:

```
Timeline:  Chunk(t=100)  Context(t=150)  Chunk(t=200)
Segments:  [Chunk(100)]  [Context(150) + Chunk(200)]
```

Context boundaries create new segments.

### Phase 3: Per-Segment Processing

Three segment types, each processed differently:

**Context-only** — Text contexts are routed through LLM markdown formatting when a transcription service is available. Otherwise written as a markdown blockquote.

**Chunk-only** — Images sent to LLM for transcription:
```
ChunkRepository.getChunkImage() → load from storage
  → Batch into groups of ≤10
  → TranscriptionService.transcribe(batch) → markdown text
```

**Context + Chunk (Q&A)** — Vision LLM answers a question using context:
```
Transcribe chunk images → question text
Load context images from disk
  → RegionImage contexts sent through vision LLM for transcription
  → QuestionAnswerService.answerQuestion()
  → LLM.visionQuery(prompt, allImages)
  → Returns markdown: Context / Question / Answer
```

### Phase 3.5: Post-Processing
```
All assembled markdown → polishMarkdownFormatting() (LLM cleanup pass)
  → OutputSanitizer.sanitize() applied to all content
```

### Phase 4: File Writing
```
Parse project URI (SAF)
  → Find or create target file in project folder
  → Append sanitized markdown with timestamp header via ContentResolver
```

### Phase 5: Completion
```
ProjectStorage.setLastUsedFile()
Delete synced session data
Update UI state (Success / PartialSuccess / Error)
```

Note: Cost tracking is handled separately in ReviewViewModel as a pre-sync estimate (see Pipeline 10), not as a post-sync recording from the sync pipeline.

---

## Pipeline 5: Local Storage

Five storage layers, all using mutex-locked atomic writes:

| Layer | Location | Manager | Format |
|-------|----------|---------|--------|
| Session metadata | `files/sessions/{id}.json` | SessionStorage | JSON |
| Chunk images | `cache/chunks/{sessionId}/*.webp` | ChunkStorage | WebP |
| Project config | `files/projects/projects.json` | ProjectStorage | JSON |
| Sync queue | `files/sync/queue.json` | SyncStorage | JSON |
| Settings | DataStore preferences | DataStore<Preferences> | Key-value |

**Corruption detection:** ChunkStorage verifies the RIFF/WEBP header on write. If a decode fails later, a `.corrupted` marker file is created and the chunk is excluded from LLM requests.

---

## Pipeline 6: Settings & Configuration

**Origin:** User input in SettingsScreen

```
UI toggle/slider → SettingsViewModel.setX()
  → Coroutine writes to DataStore
  → StateFlow emits new value
  → Observers (ViewModels, Compose) react
```

LLM configuration specifically flows through `LlmSettingsProvider`:
```
DataStore → readLlmSettings() → Triple<Provider, ApiKey, RateLimitingSafe>
  → DefaultTranscriptionServiceFactory.create(provider, key, rateLimiting)
  → Returns GeminiService | ClaudeService | OpenAiService | OllamaService
```

This allows hot-swapping LLM providers without rebuilding the app.

---

## Pipeline 7: LLM Transcription Services

**Origin:** Sync workflow (Pipeline 4)

```
TranscriptionService interface
  ├─ GeminiService
  ├─ ClaudeService
  ├─ OpenAiService
  └─ OllamaService

Methods:
  transcribe(chunks: List<ChunkData>, cleanupEnabled: Boolean, advancedFormatting: Boolean) → TranscriptionResult
  textQuery(prompt: String, systemPrompt: String?) → String
  visionQuery(prompt: String, images: List<ByteArray>, systemPrompt: String?) → String
```

**Batching:** Max 10 chunks per request to avoid token overflow.

**Rate limiting:**
- Free tier (`rateLimitingSafe=true`): Pre-check + backoff delays
- Paid tier: Send immediately, handle 429 responses

**Error handling:** Failed chunks tracked separately. `PartialSuccess` returned if some chunks fail. Retryable on next sync.

---

## Pipeline 8: Image Processing & Stitching

**Origin:** ReviewScreen display and user actions

```
Thumbnail loading:
  ChunkStorage.loadThumbnail() → _thumb.webp → small bitmap for list

Full image loading:
  ChunkStorage.loadChunk() → decode WebP → Bitmap for preview

Stitching (separate view mode):
  User selects chunks → ImageProcessor.stitchChunks()
    → Load all selected chunk files
    → Calculate total dimensions (scale if >4096px to prevent OOM)
    → Create combined bitmap, draw chunks vertically
    → Return stitched bitmap (or save as WebP)
```

Memory managed via `inSampleSize` scaling and explicit `bitmap.recycle()`.

---

## Pipeline 9: Background Sync Queue

**Origin:** User taps "Sync Later"

```
Enqueue:
  SyncRepository.queueForSync(sessionId, projectId, filename)
  → SyncStorage.addToQueue() → SyncQueueItem(status=PENDING)

Process:
  SyncRepository.processQueue() (runs when app active)
  → For each PENDING item:
      Mark IN_PROGRESS → syncSession() → COMPLETED or FAILED

Retry:
  User taps "Retry" → retryFailed() → resets FAILED items to PENDING
```

Queue persisted at `files/sync/queue.json`.

---

## Pipeline 10: Cost Tracking

**Origin:** ReviewViewModel pre-sync estimation

```
ReviewViewModel.updateCostEstimate()
  → LlmCostCalculator.estimateCost(chunkSizes, contextCount, model)
  → UI displays estimated cost before user taps "Sync"
  → DataStore updates:
      usage_total_cost (all-time)
      usage_monthly_cost (resets each month)
      usage_monthly_syncs (count)
      usage_current_month (for reset detection)
```

Cost tracking is an estimate computed in ReviewViewModel before sync, not a post-sync recording. UsageTracker is not called from SyncRepository.

---

## Pipeline 11: Destination Management

**Origin:** User configuration + sync output

```
Destination types:
  ├─ Clipboard → system clipboard
  ├─ Share Intent → Android share sheet
  ├─ Local Folder → device storage
  └─ Project Files → primary (Obsidian vaults, markdown files)

DestinationRepository routes output to configured destinations.
Primary output always writes to the selected Project file (markdown with timestamps).
```

---

## Pipeline 12: Navigation

```
Routes:
  Onboarding → accessibility + destination setup (first run)
  Overlay    → transparent capture canvas (persistent)
  Review     → session list, sync management
  Settings   → app configuration, project management
  Main       → NavHost connecting all screens
```

ViewModels share data through Koin-injected repositories. No direct screen-to-screen data passing.

---

## Pipeline 13: Onboarding

**Origin:** First app launch

```
Page 1: Welcome + overlay permission request
Page 2: Accessibility permission request (needed for region capture)
Page 3: Vault/destination setup → DestinationRepository → DataStore
Page 4: API key entry → encrypted storage
Page 5: Completion + notification permission request
```

---

## End-to-End Summary

```
User Input (Stylus/Touch)
    │
    ▼
CaptureCanvas → StrokeManager → CaptureViewModel
    │
    ▼
Chunk (WebP) ──→ ChunkStorage (disk)
    │               │
    ▼               ▼
SessionStorage (JSON) ←── Context Capture
    │
    ▼
ReviewViewModel ←── observeSessions() (reactive Flow)
    │
    ▼
User taps "Sync"
    │
    ▼
SyncRepository.syncSession()
    ├── Segment by context boundaries
    ├── Context-only → markdown quote
    ├── Chunk-only → LLM.transcribe() → markdown
    └── Q&A → LLM.visionQuery() → markdown answer
    │
    ▼
Write to project file (SAF) → Track cost → Cleanup session
    │
    ▼
UI updated with result status
```

---

## Concurrency & Safety

- **Mutexes** on all storage managers (SessionStorage, ProjectStorage, SyncStorage, ChunkStorage per-session)
- **StateFlow** for all observable data
- **Dispatchers.IO** for file operations
- **Atomic writes** via temp file + header verification + rename
- **Result types** (`StorageResult<T>`, `ImageResult<T>`) for error propagation
