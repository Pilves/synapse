# Synapse Refactoring Plan: MVP → Consumer Product

## Codebase Baseline

| Metric | Value |
|--------|-------|
| Total source lines | ~21,220 |
| Key packages | `api`, `data`, `model`, `service`, `ui`, `util`, `di` |
| Architecture | Single-module, MVVM, Koin DI, Jetpack Compose |
| Storage | Raw JSON files (`SessionStorage`, `ChunkStorage`) |
| LLM layer | In-app direct API calls (Claude, Gemini, OpenAI, Ollama) |
| Drawing | Quadratic bezier smoothing, fixed-width strokes |

---

## Phase 1: UX Hardening ("It Just Works")

### 1.1 Ink Smoothing & Latency

**Current state:**
- `StrokeManager.kt` (255 lines) stores `List<Offset>` per stroke with fixed `strokeWidth: Float = 4f`
- `CaptureCanvas.kt:318-354` — `createSmoothPath()` uses quadratic bezier interpolation between consecutive raw points
- No velocity tracking, no pressure data, no variable width
- The `Stroke` data class at `StrokeManager.kt:15-18` has no pressure/velocity fields

**What needs to change:**

1. **Extend `Stroke` data model** (`StrokeManager.kt:15-18`)
   - Replace `List<Offset>` with `List<StrokePoint>` where:
     ```kotlin
     data class StrokePoint(
         val position: Offset,
         val pressure: Float = 0.5f,   // 0..1, from MotionEvent or simulated
         val timestamp: Long = 0L       // for velocity calculation
     )
     ```
   - Keep `strokeWidth` as base width; actual rendered width = `baseWidth * pressureFactor`

2. **Capture pressure + timestamps** (`CaptureCanvas.kt:170-224`)
   - In the `pointerInput` gesture handler, extract `pressure` from `PointerInputChange` (available on stylus hardware) and `System.nanoTime()` for timestamps
   - For non-pressure devices (finger), simulate pressure from velocity: high velocity → thinner stroke, low velocity → thicker

3. **Implement Catmull-Rom spline interpolation** (replace `createSmoothPath` at `CaptureCanvas.kt:318-354`)
   - Catmull-Rom passes through all control points (unlike cubic bezier which doesn't), giving more natural-feeling curves
   - Algorithm: for each segment between points P1 and P2, use P0 and P3 as control tangents
   - Interpolate additional sub-points between samples to increase smoothness (e.g., 4 sub-steps per segment)
   - Each interpolated point gets an interpolated width from the pressure curve

4. **Variable-width stroke rendering** (new `drawVariableWidthStroke` in `CaptureCanvas.kt`)
   - Instead of a single `drawPath()` with uniform `Stroke(width=...)`, render the stroke as a filled polygon or sequence of circles/quads
   - Technique: at each point, compute a perpendicular offset proportional to width, build left/right outline paths, fill the enclosed shape
   - Apply the same rendering in `StrokeManager.toBitmap()` and `toBitmapForOcr()` for consistent OCR output

5. **Evaluate ink-stroke-modeler integration**
   - Google's [ink-stroke-modeler](https://github.com/nicholasgasior/ink-stroke-modeler) is C++; wrapping via JNI adds complexity
   - Recommendation: implement Catmull-Rom + velocity-width natively in Kotlin first. If results aren't satisfactory on low-end devices, then evaluate JNI binding
   - The pure-Kotlin approach avoids NDK build complexity and keeps the APK smaller

**Files to modify:**
| File | Change |
|------|--------|
| `StrokeManager.kt` | New `StrokePoint` data class, update `Stroke`, update `toBitmap`/`toBitmapForOcr` rendering |
| `CaptureCanvas.kt` | Capture pressure/timestamp, replace `createSmoothPath`, add `drawVariableWidthStroke` |
| `CaptureViewModel.kt` | Pass pressure/timestamp through `onDrawStart`/`onDrawMove` |

**Estimated complexity:** Medium-High. Core drawing pipeline change affects capture, display, and bitmap export.

---

### 1.2 Robust Permission Recovery ("Self-Healing")

**Current state:**
- `PermissionHelper.kt` (221 lines) checks overlay, notification, and storage permissions but has no monitoring/recovery loop
- `SynapseAccessibilityService.kt:17-19` uses `@Volatile instance` singleton — when the OS kills the service, `getInstance()` returns `null`, and features silently degrade
- `OverlayService.kt:512-521` checks `screenshotManager.hasPermission()` only at startup; if MediaProjection dies mid-session, region capture fails silently
- No proactive detection of accessibility service death
- No user-facing recovery UI in the overlay

**What needs to change:**

1. **Accessibility Service health monitor** (new `ServiceHealthMonitor.kt`)
   - Create a periodic check (every 30s via `Handler.postDelayed` or coroutine `delay` loop) inside `OverlayService`
   - Check `SynapseAccessibilityService.getInstance() != null` AND `SynapseAccessibilityService.isEnabled(context)`
   - Track state transitions: `HEALTHY → DEGRADED → DEAD`
   - Expose as `StateFlow<ServiceHealth>` for the overlay UI to observe

2. **MediaProjection death detection** (`OverlayService.kt`)
   - Register a `MediaProjection.Callback` on the projection object (via `screenshotManager`) to detect `onStop()`
   - When triggered, set `screenshotManager.invalidateProjection()` and emit `ServiceHealth.DEGRADED`

3. **Overlay warning indicator** (modify `FloatingBubble` composable, `OverlayService.kt:1026+`)
   - When `ServiceHealth != HEALTHY`, show a small warning badge (amber ⚠ icon) on the floating bubble
   - When tapped, show a bottom-sheet-style overlay card explaining what's broken:
     - Accessibility dead → "Synapse needs accessibility access to capture context. Tap to re-enable." → Deep-link: `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)`
     - MediaProjection dead → "Screen capture permission expired. Tap to re-grant." → Trigger `requestScreenCapturePermission()`
   - Auto-dismiss the warning if the service recovers (user re-enabled and comes back to app)

4. **Graceful degradation flags**
   - When accessibility is dead: disable region text extraction, hide the region-select toolbar button, but keep basic handwriting capture working
   - When projection is dead: disable region screenshot, but keep text extraction if accessibility is alive
   - Show which features are available vs degraded in the toolbar with subtle visual cues

5. **PermissionHelper enhancements** (`PermissionHelper.kt`)
   - Add `hasAccessibilityPermission(context): Boolean` (mirrors `isEnabled()` on the service)
   - Add `getAccessibilitySettingsIntent(): Intent` for deep-linking
   - Add `getAllServiceStatuses(): Map<String, Boolean>` combining overlay, accessibility, projection

**Files to modify:**
| File | Change |
|------|--------|
| New: `ServiceHealthMonitor.kt` | Health check loop, StateFlow emission |
| `OverlayService.kt` | Integrate monitor, add MediaProjection.Callback, update bubble UI |
| `PermissionHelper.kt` | Accessibility check helpers |
| `SynapseAccessibilityService.kt` | Emit health events on connect/disconnect |
| `AppModule.kt` | Register ServiceHealthMonitor in Koin |

**Estimated complexity:** Medium. Mostly new code, limited changes to existing drawing pipeline.

---

### 1.3 Software Palm Rejection

**Current state:**
- `TouchDifferentiatingOverlayView` (`OverlayService.kt:909-1020`) uses `FLAG_NOT_TOUCHABLE` window flag toggling based on stylus hover/touch detection
- `CaptureCanvas.kt:170-224` differentiates `PointerType.Stylus` vs `PointerType.Touch` but has no touch-area-based filtering
- No filtering of large touch contacts (palm), multi-finger rejection, or edge-of-screen rejection
- In `BOTH_WRITE` mode, a palm resting on the screen will draw unwanted strokes

**What needs to change:**

1. **Extract touch contact area** (`CaptureCanvas.kt` gesture handler)
   - `MotionEvent` provides `getTouchMajor()` and `getTouchMinor()` for contact ellipse dimensions
   - In the `pointerInteropFilter` or via a custom `PointerInputModifier`, read the raw `MotionEvent` to get these values
   - Compose's `PointerInputChange` doesn't expose touch size directly, so we need `pointerInteropFilter` as an additional layer

2. **Palm rejection heuristics** (new `PalmRejectionFilter.kt`)
   - **Area threshold:** If `touchMajor * touchMinor > PALM_AREA_THRESHOLD` (calibrate per device density, e.g., 2500 sq pixels on mdpi), classify as palm → ignore
   - **Edge rejection:** If touch starts within 15dp of screen edges and contact area > small threshold, likely palm → ignore
   - **Multi-touch rejection:** If >2 simultaneous touch points with large areas, assume palm rest → ignore all non-stylus touches
   - **Velocity filter:** Palm contacts tend to be slow-moving with large areas; fast small contacts are fingers → allow

3. **Integration into CaptureCanvas** (`CaptureCanvas.kt:170-224`)
   - Wrap the gesture handler to pass each `MotionEvent` through `PalmRejectionFilter` before processing
   - In `BOTH_WRITE` mode: reject palm touches, allow small-contact finger touches
   - In `STYLUS_WRITE_FINGER_SCROLL` mode: palm rejection is less critical (fingers pass through anyway) but still useful to avoid accidental toolbar taps

4. **Integration into TouchDifferentiatingOverlayView** (`OverlayService.kt:953-975`)
   - In `dispatchTouchEvent()`, before calling `super.dispatchTouchEvent(event)`, check touch area
   - If classified as palm, return `false` (pass through to underlying app)

5. **Device-specific calibration**
   - Different devices report different `touchMajor`/`touchMinor` scales
   - Normalize by `displayMetrics.density`
   - Allow user to adjust sensitivity in settings (optional, Phase 2)

**Files to modify:**
| File | Change |
|------|--------|
| New: `PalmRejectionFilter.kt` | Heuristic engine with area, edge, multi-touch checks |
| `CaptureCanvas.kt` | Add `pointerInteropFilter` layer before gesture handler |
| `OverlayService.kt` (TouchDifferentiatingOverlayView) | Palm check in `dispatchTouchEvent()` |
| `CaptureCanvas.kt` (InputMode) | Consider new `STYLUS_PREFERRED` mode with palm rejection |

**Estimated complexity:** Medium. Self-contained new module with targeted integration points.

---

## Phase 2: Feature Expansion

### 2.1 Wire Up Intent System (Calendar, Email, Tasks)

**Current state:**
- `IntentType.kt` defines NOTE, TASK, QUESTION, REMINDER, REACTION with `DetectedIntent` + `IntentData` sealed class
- `PromptTemplateV2.kt` already instructs the LLM to detect intents and extract structured data (deadlines, times, questions)
- `ReminderManager.kt` (55 lines) has `createAlarm()` and `createCalendarEvent()` — functional but not wired to any UI
- No post-transcription intent routing. After transcription, results are just displayed in ReviewScreen

**What needs to change:**

1. **Intent action router** (new `IntentActionRouter.kt`)
   - Receives `List<DetectedIntent>` from transcription result
   - Routes each intent to its handler:
     - `TASK` with deadline → `ReminderManager.createCalendarEvent()` or create Todoist task
     - `REMINDER` with time → `ReminderManager.createAlarm()`
     - `QUESTION` → Query LLM via `textQuery()` and present answer inline
     - `REACTION` → Copy reaction text to clipboard, show toast
     - `NOTE` → Default: save to configured destination (file, clipboard)

2. **Intent confirmation UI** (new composable in `ui/overlay/`)
   - When `needsConfirmation == true` (confidence < 0.8), show a compact dialog:
     ```
     ┌─────────────────────────────┐
     │ 📅 Create calendar event?   │
     │ "Meeting with John"         │
     │ Tomorrow at 2:00 PM         │
     │                             │
     │ [Create Event]  [Just Save] │
     └─────────────────────────────┘
     ```
   - For high-confidence intents, execute automatically with an undo toast ("Created event. Undo?")

3. **Email drafting via accessibility context** (new `EmailDraftHandler.kt`)
   - When user is over Gmail/Outlook (detected via `SynapseAccessibilityService.currentSourceApp`)
   - And writes something like "polite decline" or "reply saying..."
   - Extract the email body from accessibility nodes
   - Send to LLM: `{handwriting: "polite decline", emailBody: "...", instruction: "draft reply"}`
   - Copy result to clipboard and show toast "Draft copied. Paste to reply."

4. **Expand ReminderManager** (`ReminderManager.kt`)
   - Add `createTask()` — generic task creation intent
   - Add NLP time parsing: "tomorrow at 2pm", "next Friday", "in 30 minutes" → `Calendar` millis
   - Currently relies on LLM to output `parsedTime` as epoch millis; add a local fallback parser using `java.time` APIs

5. **Wire into ReviewScreen / OverlayService post-capture flow**
   - After `SyncRepository` returns transcription results with intents:
     - If overlay is visible → show intent cards in overlay
     - If in ReviewScreen → show action buttons per note with detected intent

**Files to modify:**
| File | Change |
|------|--------|
| New: `IntentActionRouter.kt` | Central routing of detected intents |
| New: `IntentConfirmationDialog.kt` | Composable confirmation UI |
| New: `EmailDraftHandler.kt` | Gmail/email context + LLM drafting |
| `ReminderManager.kt` | Expand with task creation, local time parsing |
| `OverlayService.kt` | Post-capture intent handling |
| Review UI files | Action buttons per intent type |

---

### 2.2 Cloud Integrations (Notion, Todoist, Google Docs)

**Current state:**
- Output destinations exist at `data/destination/` but only support local file (Obsidian vault via SAF) and clipboard
- No OAuth2 infrastructure
- No cloud API clients

**What needs to change:**

1. **OAuth2 infrastructure** (new `auth/` package)
   - `OAuthManager.kt` — Handle authorization code flow with PKCE
   - `TokenStorage.kt` — Encrypted SharedPreferences for refresh/access tokens
   - Per-provider configs: Notion, Todoist, Google (OAuth client IDs)

2. **Cloud destination implementations** (new files in `data/destination/`)
   - `NotionDestination.kt` — Create page in database via Notion API
   - `TodoistDestination.kt` — Create task via Todoist REST API
   - `GoogleDocsDestination.kt` — Append to daily scratchpad doc

3. **Destination picker UI** in settings — multi-select destinations per intent type

4. **Monetization gate** — Cloud destinations behind subscription check (Phase 3 dependency)

---

### 2.3 Voice Context (Microphone + LLM)

**What needs to change:**

1. **Mic button in overlay toolbar** (`OverlayService.kt` composable toolbar)
2. **Audio capture service** — `MediaRecorder` or `AudioRecord` with permission handling
3. **Transcription pipeline** — Send audio to Whisper API or Gemini multimodal
4. **Multi-modal LLM prompt** — `{image} + {audioTranscript} + {screenContext}`
5. **Permission addition** — `RECORD_AUDIO` in manifest and runtime flow

---

## Phase 3: Business Model (Monetization)

### 3.1 Pro Subscription ($5/month)

**What needs to change:**

1. **Backend proxy server** (new project, Ktor or Spring Boot)
   - Endpoint: `POST /api/transcribe` accepting `(image, contextJSON, userToken)`
   - Backend holds API keys, routes to Azure OpenAI or Anthropic
   - User authenticates via Google Sign-In → JWT token

2. **Google Play Billing integration** (in-app)
   - BillingClient v6+ for subscriptions
   - `SubscriptionManager.kt` — check entitlement, manage lifecycle
   - Gate cloud features behind subscription status

3. **Auth flow** — Google Sign-In replaces manual API key entry for Pro users

### 3.2 Power User One-Time Purchase ($20)

- BYOK mode (existing functionality)
- Custom prompt editing (existing `setCustomPrompt()`)
- Local Ollama support (existing `OllamaService`)
- Gate behind one-time purchase IAP

### 3.3 Cost Optimization

**Current state:**
- `ImageProcessor.kt` already crops and scales to max 800px
- Full-color WebP sent to vision models

**What needs to change:**

1. **Grayscale conversion** (`ImageProcessor.kt`)
   - Add `toGrayscale()` before encoding — handwriting is monochrome, saves ~60% payload
   - Only apply for transcription, not for region screenshots (which may need color)

2. **Two-pass LLM logic** (modify transcription flow)
   - Pass 1: If accessibility text is available and sufficient, use cheap text-only model (Gemini Flash)
   - Pass 2: Only invoke vision model if text is insufficient or user drew a diagram
   - Decision logic: if `contexts` contain enough `RegionText`/`SelectedText` → skip vision

---

## Phase 4: Codebase Maturity

### 4.1 Modularize LLM Layer

**Current state:**
- `api/` package contains all LLM logic in-app: `ClaudeService`, `GeminiService`, `OpenAIService`, `OllamaService`
- `PromptTemplateV2` hardcoded in app
- `TranscriptionServiceFactory` creates services locally

**What needs to change:**

1. **Extract shared API module** (new Gradle module `:api`)
   - Move `TranscriptionService` interface, `PromptTemplateV2`, models
   - App module depends on `:api`

2. **Add remote transcription client** (`RemoteTranscriptionService.kt`)
   - Implements `TranscriptionService`
   - Sends `(image, contextJSON, userToken)` to your backend
   - Backend handles prompt construction and provider routing

3. **Provider routing becomes server-side**
   - App sends abstract request; server decides Claude vs Gemini vs DeepSeek
   - Prompt updates ship server-side without app updates

### 4.2 Migrate to Room Database

**Current state:**
- `SessionStorage.kt` (816 lines) reads/writes JSON files with atomic rename
- `ChunkStorage.kt` (794 lines) stores WebP files + metadata
- No indexing, no querying beyond "get all" / "get by ID"
- All sessions loaded into memory for observation (`observeSessions()` returns `StateFlow<List<Session>>`)

**What needs to change:**

1. **Room entities**
   ```kotlin
   @Entity(tableName = "sessions")
   data class SessionEntity(
       @PrimaryKey val id: String,
       val startedAt: Long,
       val endedAt: Long?,
       val sourceApp: String?,
       val tags: String? // comma-separated or JSON array
   )

   @Entity(tableName = "chunks", foreignKeys = [...])
   data class ChunkEntity(
       @PrimaryKey val id: String,
       val sessionId: String,
       val index: Int,
       val filePath: String,
       val createdAt: Long,
       val isCorrupted: Boolean
   )

   @Entity(tableName = "contexts")
   data class ContextEntity(
       @PrimaryKey val id: String,
       val sessionId: String,
       val type: String, // "SelectedText", "RegionText", etc.
       val data: String  // JSON blob
   )
   ```

2. **DAOs with query support**
   - `getSessionsByApp(packageName)` — "Show notes taken over Chrome"
   - `searchNotes(query)` — FTS on transcribed text
   - `getSessionsByDateRange(start, end)` — date filtering
   - `getSessionsByTag(tag)` — tag-based filtering

3. **Migration strategy**
   - Keep `SessionStorage` JSON read capability for migration
   - On first launch with Room: scan existing JSON files, import into Room
   - Delete JSON files after successful import
   - ChunkStorage (WebP files) stays on filesystem — Room just stores metadata paths

4. **Replace `observeSessions()` StateFlow**
   - Room's `Flow<List<SessionEntity>>` replaces manual `MutableStateFlow` management
   - Eliminates the in-memory caching and manual flow emission in `SessionStorage`

### 4.3 Harden Accessibility for Play Store

**Current state:**
- `accessibility_service_config.xml` requests: `typeViewTextSelectionChanged|typeWindowStateChanged|typeWindowContentChanged`
- `canRetrieveWindowContent="true"` — required for text extraction
- No privacy policy reference in config
- No "basic mode" fallback

**What needs to change:**

1. **Basic Mode without Accessibility**
   - All handwriting capture + LLM transcription works without accessibility
   - Region capture falls back to screenshot-only (no text extraction)
   - No auto-context (current app/URL) — user manually provides context
   - Settings toggle: "Enable Smart Context (requires Accessibility Service)"

2. **Accessibility justification**
   - Update `accessibility_description` string to clearly state: "Synapse uses accessibility to capture text from your screen to provide context for your handwritten notes"
   - Add `android:settingsActivity` to `accessibility_service_config.xml` pointing to a dedicated explanation activity
   - Privacy policy must explicitly describe data collected and how it's used

3. **Minimize accessibility scope**
   - Consider requesting only `typeWindowStateChanged` (for app detection) in basic accessibility mode
   - Only add `typeViewTextSelectionChanged` and `typeWindowContentChanged` when user enables "Full Context" mode

---

## Implementation Priority & Dependency Graph

```
Phase 1 (UX Hardening) — No external dependencies, can start immediately
├── 1.1 Ink Smoothing ─────── standalone, affects drawing pipeline
├── 1.2 Permission Recovery ── standalone, affects service layer
└── 1.3 Palm Rejection ─────── standalone, affects input layer

Phase 2 (Features) — Depends on Phase 1 being stable
├── 2.1 Intent Wiring ───────── depends on stable transcription (existing)
├── 2.2 Cloud Integrations ──── depends on 2.1 (routes intents to cloud)
└── 2.3 Voice Context ────────── independent, can parallel with 2.1

Phase 3 (Monetization) — Depends on Phase 2 features existing
├── 3.1 Pro Subscription ─────── depends on backend (4.1) + cloud (2.2)
├── 3.2 Power User Purchase ──── depends on billing infra (3.1)
└── 3.3 Cost Optimization ────── independent, can start during Phase 2

Phase 4 (Tech Debt) — Can interleave with Phase 2
├── 4.1 LLM Modularization ──── blocks 3.1 (backend proxy)
├── 4.2 Room Migration ────────── independent, start during Phase 2
└── 4.3 Accessibility Hardening ─ should do before Play Store submission
```

## Recommended Execution Order

| Sprint | Work Items | Rationale |
|--------|-----------|-----------|
| 1 | 1.1 Ink Smoothing + 1.3 Palm Rejection | Core UX — these run in parallel since they touch different parts of the input pipeline |
| 2 | 1.2 Permission Recovery + 4.3 Accessibility Hardening | Reliability + Play Store readiness |
| 3 | 2.1 Intent Wiring + 4.2 Room Migration | Biggest value-add feature + storage foundation |
| 4 | 4.1 LLM Modularization + 3.3 Cost Optimization | Backend prep + reduce running costs |
| 5 | 2.2 Cloud Integrations + 3.1 Pro Subscription | Revenue features — need backend from Sprint 4 |
| 6 | 2.3 Voice Context + 3.2 Power User Purchase | Polish features + complete monetization |

## Key Architectural Decisions

1. **Catmull-Rom over ink-stroke-modeler JNI** — Avoids NDK complexity, keeps APK small, sufficient quality for handwriting
2. **Room over raw JSON** — Enables queries, scales with history, eliminates JSON parsing jank
3. **Server-side prompt routing** — Decouples provider choice from app releases, enables A/B testing
4. **Basic Mode fallback** — De-risks Play Store review of accessibility usage
5. **OAuth2 with PKCE** — Standard secure flow for cloud integrations without backend secret exposure
6. **Two-pass LLM** — Dramatic cost reduction for text-heavy captures where vision is unnecessary
