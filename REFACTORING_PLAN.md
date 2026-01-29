# Synapse Feature Expansion — Technical Specification

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

# PART A: CRITICAL UX HARDENING

Ship these with v1. Not features — table stakes.

---

## Critical 1: Permission Recovery (Self-Healing)

### Problem

When Android kills the Accessibility Service or MediaProjection token (battery optimization, memory pressure), features break silently. User thinks app is broken, uninstalls.

### Current Code Analysis

| Component | File | Line | Current Behavior |
|-----------|------|------|-----------------|
| Accessibility singleton | `SynapseAccessibilityService.kt` | 17-19 | `@Volatile instance` set to `null` in `onDestroy()`. No recovery signal. |
| Accessibility check | `SynapseAccessibilityService.kt` | 21-30 | `isEnabled()` parses `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` — static check only, never called proactively |
| Overlay permission check | `OverlayService.kt` | 493 | `Settings.canDrawOverlays()` checked once at `startOverlay()` |
| MediaProjection check | `OverlayService.kt` | 512-521 | `screenshotManager.hasPermission()` checked at startup; stale flag detected but only on start |
| Projection mid-session | `OverlayService.kt` | 280-282 | `captureRegion()` returns null → logs warning, shows "[Screenshot failed]" text → no recovery action |
| Permission helper | `PermissionHelper.kt` | 192-197 | `checkAllPermissions()` returns overlay + notification + storage — accessibility is **not included** |
| Region text extraction | `OverlayService.kt` | 244-248 | `SynapseAccessibilityService.getInstance()?.getTextInRegion()` — returns null silently when service dead |
| Bubble UI | `OverlayService.kt` | 1026-1107 | `FloatingBubble` composable — no health state parameter, no warning badge |

### Implementation Plan

#### New file: `service/PermissionHealthMonitor.kt`

```kotlin
class PermissionHealthMonitor(private val context: Context) {
    data class PermissionHealth(
        val overlayGranted: Boolean,
        val accessibilityEnabled: Boolean,
        val mediaProjectionActive: Boolean
    )

    private val _health = MutableStateFlow(checkHealth())
    val health: StateFlow<PermissionHealth> = _health
    private val checkInterval = 5000L  // 5 seconds

    fun startMonitoring(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            while (true) {
                _health.value = checkHealth()
                delay(checkInterval)
            }
        }
    }

    private fun checkHealth(): PermissionHealth { ... }
}
```

#### Modifications required

| File | Location | Change |
|------|----------|--------|
| `OverlayService.kt:110-131` | Service fields | Add `permissionHealthMonitor` field, inject or create in `onCreate()` |
| `OverlayService.kt:151-182` | `onCreate()` | Call `permissionHealthMonitor.startMonitoring(serviceScope)`, collect health state |
| `OverlayService.kt:1026-1107` | `FloatingBubble` composable | Add `showWarning: Boolean` and `onWarningClick: () -> Unit` parameters; render amber badge overlay when `showWarning == true` |
| `OverlayService.kt:628-654` | `showFloatingBubble()` | Pass health state to `FloatingBubble` composable; wire warning click to open recovery dialog |
| `PermissionHelper.kt:192-197` | `checkAllPermissions()` | Add `"accessibility"` key using `SynapseAccessibilityService.isEnabled(context)` |
| `PermissionHelper.kt` | New methods | Add `hasAccessibilityPermission()`, `getAccessibilitySettingsIntent()` |
| `SynapseAccessibilityService.kt:40-51` | `onServiceConnected()` | Broadcast or update a shared health state flow |
| `SynapseAccessibilityService.kt:217-219` | `onDestroy()` | Broadcast disconnection |
| `AppModule.kt:220-260` | `v2Module` | Register `PermissionHealthMonitor` as singleton |

#### New composable: `PermissionRecoveryDialog`

Wire into OverlayService. When `health.accessibilityEnabled == false`:
- Show `AlertDialog` with "Context capture was disabled. Tap Fix to re-enable."
- Confirm → `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)`
- Dismiss → hide, show again on next bubble tap

#### Graceful degradation

In `OverlayService.kt:1198-1214` (region capture toolbar button):
- When `!health.accessibilityEnabled && !health.mediaProjectionActive` → gray out region button, show tooltip "Requires permissions"
- When only accessibility dead → region still works via screenshot fallback
- When only projection dead → region works via text extraction only

### Effort: 0.5 days

---

## Critical 2: Palm Rejection

### Problem

User rests palm on tablet while writing → overlay registers massive touch → interferes with underlying app or creates garbage strokes. Current `BOTH_WRITE` mode at `OverlayService.kt:1141` has zero palm filtering.

### Current Code Analysis

| Component | File | Line | Current Behavior |
|-----------|------|------|-----------------|
| Input differentiation | `CaptureCanvas.kt` | 170-224 | `pointerInput` gesture handler checks `PointerType.Stylus` vs `PointerType.Touch` — binary yes/no, no touch geometry |
| Touch dispatch | `OverlayService.kt` | 953-975 | `TouchDifferentiatingOverlayView.dispatchTouchEvent()` — checks `TOOL_TYPE_STYLUS` vs finger, no area check |
| Stylus-active flag | `OverlayService.kt` | 958-966 | When stylus is detected, keeps overlay touchable — but doesn't reject concurrent palm contacts |
| Input mode | `CaptureCanvas.kt` | 175-185 | `BOTH_WRITE` accepts any `STYLUS || FINGER` — palm touch accepted as drawing input |
| Canvas overlay | `OverlayService.kt` | 716-728 | Window params use `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN` — no touch filtering flags |

### Implementation Plan

#### New file: `ui/overlay/PalmRejectionFilter.kt`

```kotlin
class PalmRejectionFilter {
    companion object {
        private const val MAX_TOUCH_MAJOR = 100f
        private const val MAX_TOUCH_MINOR = 60f
        private const val MAX_PRESSURE_FOR_PALM = 0.15f
    }

    private var activeStylus = false

    fun filterEvent(event: MotionEvent): FilterResult {
        // Always accept stylus
        if (event.getToolType(0) == TOOL_TYPE_STYLUS || TOOL_TYPE_ERASER) {
            activeStylus = true
            return FilterResult.Accept(event)
        }
        // Reject finger while stylus is active
        if (activeStylus && event.getToolType(0) == TOOL_TYPE_FINGER) {
            if (event.action == ACTION_UP && event.pointerCount == 1) activeStylus = false
            return FilterResult.Reject("Finger rejected while stylus active")
        }
        // Check palm geometry
        return if (isPalmTouch(event)) FilterResult.Reject("Palm") else FilterResult.Accept(event)
    }
}
```

#### Modifications required

| File | Location | Change |
|------|----------|--------|
| `CaptureCanvas.kt:133-226` | Write mode modifier block | Add `pointerInteropFilter` **before** the `pointerInput` block to intercept raw `MotionEvent`, run through `PalmRejectionFilter`, consume if rejected |
| `CaptureCanvas.kt:101-111` | `CaptureCanvas` signature | Add optional `palmRejectionEnabled: Boolean = true` parameter |
| `OverlayService.kt:953-975` | `dispatchTouchEvent()` | Before `super.dispatchTouchEvent(event)`, call `palmFilter.filterEvent(event)` — return `false` if rejected |
| `OverlayService.kt:909-920` | `TouchDifferentiatingOverlayView` class | Add `private val palmFilter = PalmRejectionFilter()` field |

#### Device-specific tuning

Add `PalmRejectionConfig` object with manufacturer-based defaults:
- Samsung (S Pen): `trustStylusExclusively = true`, lower thresholds since S Pen already rejects
- Huawei: Higher `maxTouchMajor` (120f) since M-Pencil reports differently
- Default: 100f major, 60f minor

### Effort: 0.5 days

---

## Critical 3: Accessibility Service Play Store Compliance

### Problem

Google Play reviews Accessibility Services strictly. Vague descriptions = rejection. Current config is minimal.

### Current Code Analysis

| Component | File | Line | Current State |
|-----------|------|------|--------------|
| XML config | `accessibility_service_config.xml` | 1-9 | Missing `android:summary`, missing `android:settingsActivity`, missing `android:accessibilityFlags` for `flagReportViewIds\|flagRetrieveInteractiveWindows` |
| Description string | `strings.xml` | 25 | One-liner: "Synapse uses accessibility to capture text from your screen..." — needs expansion with DO/DON'T list |
| No basic mode | — | — | App requires accessibility for context; no fallback path |
| Privacy policy | — | — | No PRIVACY.md or in-app disclosure |

### Implementation Plan

#### Update `accessibility_service_config.xml`

```xml
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/accessibility_service_description"
    android:summary="@string/accessibility_service_summary"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewTextSelectionChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="100"
    android:settingsActivity=".ui.settings.AccessibilitySettingsActivity" />
```

#### Update `strings.xml`

Replace one-liner with detailed DO/DON'T description (required by Google Play policy):
```xml
<string name="accessibility_service_description">
    Synapse uses accessibility to:\n\n
    • Read selected text to add context to your handwritten notes\n
    • Detect the app and webpage you\'re viewing for source attribution\n
    • Extract text from screen regions you explicitly select\n\n
    Synapse does NOT:\n
    • Monitor your activity in the background\n
    • Store or transmit screen content without your action\n
    • Access passwords, financial data, or private messages\n\n
    All processing is initiated only when you open the Synapse overlay.
</string>
<string name="accessibility_service_summary">Enables context capture for handwritten notes</string>
```

#### New: Basic Mode fallback (`SynapseCapabilities.kt`)

```kotlin
class SynapseCapabilities(private val context: Context) {
    enum class Mode { FULL, BASIC, MINIMAL }
    enum class Feature { HANDWRITING, AUTO_CONTEXT, SELECTED_TEXT, REGION_TEXT_GRAB, REGION_SCREENSHOT, INTENT_DETECTION }

    fun getCurrentMode(): Mode {
        val hasAccessibility = SynapseAccessibilityService.isEnabled(context)
        val hasProjection = ScreenshotManager.hasActiveProjection()
        return when {
            hasAccessibility -> Mode.FULL
            hasProjection -> Mode.BASIC
            else -> Mode.MINIMAL
        }
    }
}
```

#### Modifications required

| File | Location | Change |
|------|----------|--------|
| `accessibility_service_config.xml` | Entire file | Add `summary`, `settingsActivity`, `accessibilityFlags` |
| `strings.xml:25` | Accessibility description | Replace with detailed DO/DON'T text |
| New: `SynapseCapabilities.kt` | `service/` | Mode detection + feature availability |
| New: `AccessibilitySettingsActivity.kt` | `ui/settings/` | Explanation screen (settingsActivity target) |
| `OverlayService.kt:1148-1150` | Region mode handling | Check `SynapseCapabilities.getCurrentMode()` before enabling region features |
| `AndroidManifest.xml` | Activities | Register `AccessibilitySettingsActivity` |

#### Privacy policy

Create `PRIVACY.md` with sections on:
- Accessibility Service Usage (what we read, when, what we don't)
- Data Handling (processed on capture only, sent to configured LLM, no permanent screen storage)
- API Key handling (stored locally in DataStore, never transmitted to our servers in BYOK mode)

### Effort: 0.5 days

---

## Critical 4: Ink Smoothing

### Problem

Raw touch points create jagged strokes. Feels cheap, not like paper. Current quadratic bezier smoothing uses the previous point as control point — produces OK curves but no variable width.

### Current Code Analysis

| Component | File | Line | Current Behavior |
|-----------|------|------|-----------------|
| Stroke model | `StrokeManager.kt` | 15-18 | `data class Stroke(val points: List<Offset>, val strokeWidth: Float = 4f)` — flat point list, fixed width |
| Path creation | `CaptureCanvas.kt` | 318-354 | `createSmoothPath()` — quadratic bezier: uses midpoint of (prev, current) as control point. Produces uniform-width paths. |
| Stroke rendering | `CaptureCanvas.kt` | 281-313 | `drawStrokeWithOutline()` — single `drawPath()` call with `Stroke(width=strokeWidth)`. No per-point width variation. |
| Bitmap export | `StrokeManager.kt` | 110-159 | `toBitmap()` — same quadratic bezier, same fixed width paint. Dark outline + white stroke. |
| OCR export | `StrokeManager.kt` | 173-253 | `toBitmapForOcr()` — same path logic, black stroke on white background, cropped to bounds, max 800px. |
| Point capture | `CaptureCanvas.kt` | 199-221 | `viewModel.onDrawStart(down.position)` / `onDrawMove(currentPos)` — only `Offset` (x,y). No pressure, no timestamp. |
| ViewModel | `CaptureViewModel.kt` | 188-242 | `onDrawStart(offset)`, `onDrawMove(offset)`, `onDrawEnd(strokeWidth)` — `Offset` only |

### Implementation Plan

#### New file: `ui/overlay/StrokeSmoother.kt`

Catmull-Rom spline interpolation with velocity-based width variation:

```kotlin
class StrokeSmoother {
    fun smoothStroke(rawPoints: List<StrokePoint>): List<SmoothSegment> {
        // Catmull-Rom: for each 4-point window [P0,P1,P2,P3],
        // compute cubic bezier control points for segment P1→P2
        // Width at each point derived from velocity (fast = thin, slow = thick)
    }

    private fun catmullRomToBezier(p0, p1, p2, p3, tension: Float = 0.5f): Pair<PointF, PointF>
    private fun calculateVelocity(p1: StrokePoint, p2: StrokePoint): Float
    private fun velocityToWidth(velocity: Float): Float  // minWidth=1.5f, maxWidth=5f
}

sealed class SmoothSegment {
    data class Line(val start, val end, val width: Float)
    data class Bezier(val start, val cp1, val cp2, val end, val startWidth, val endWidth: Float)
}
```

#### New data class: `StrokePoint`

```kotlin
data class StrokePoint(
    val position: Offset,
    val pressure: Float = 0.5f,   // 0..1 from MotionEvent or simulated
    val timestamp: Long = 0L       // SystemClock.uptimeMillis()
)
```

#### Modifications required

| File | Location | Change |
|------|----------|--------|
| `StrokeManager.kt:15-18` | `Stroke` data class | Change `points: List<Offset>` → `points: List<StrokePoint>`, keep `strokeWidth` as base |
| `CaptureViewModel.kt:92-93` | `_currentStroke` flow | Change `MutableStateFlow<List<Offset>>` → `MutableStateFlow<List<StrokePoint>>` |
| `CaptureViewModel.kt:188-206` | `onDrawStart()` | Accept `StrokePoint` instead of `Offset` |
| `CaptureViewModel.kt:213-217` | `onDrawMove()` | Accept `StrokePoint` instead of `Offset` |
| `CaptureViewModel.kt:224-242` | `onDrawEnd()` | Create `Stroke` with `StrokePoint` list |
| `CaptureCanvas.kt:199` | `onDrawStart` call | Extract `pressure` from `PointerInputChange.pressure`, create `StrokePoint` |
| `CaptureCanvas.kt:217` | `onDrawMove` call | Same — pass `StrokePoint` with pressure and `System.currentTimeMillis()` |
| `CaptureCanvas.kt:281-313` | `drawStrokeWithOutline()` | Replace with `drawSmoothStroke()` that uses `StrokeSmoother` and renders `SmoothSegment` list with variable width |
| `CaptureCanvas.kt:318-354` | `createSmoothPath()` | Replace with Catmull-Rom implementation (or delegate to `StrokeSmoother`) |
| `StrokeManager.kt:110-159` | `toBitmap()` | Update to use smoothed path + variable width for bitmap export |
| `StrokeManager.kt:173-253` | `toBitmapForOcr()` | Same update for OCR bitmap |
| `CaptureCanvas.kt:237-256` | Stroke rendering loop | Both completed strokes and current stroke need updated rendering |

#### Pressure sensitivity (stylus hardware)

```kotlin
// In CaptureCanvas gesture handler (line ~199):
val pressure = down.pressure  // Compose PointerInputChange exposes this
val point = StrokePoint(
    position = down.position,
    pressure = if (pressure > 0f) pressure else 0.5f,  // fallback for non-pressure devices
    timestamp = System.currentTimeMillis()
)
viewModel.onDrawStart(point)
```

For finger input without pressure hardware, simulate from velocity:
```kotlin
fun velocityToSimulatedPressure(velocity: Float): Float {
    // Fast = light press (thin), slow = heavy press (thick)
    return (1f - (velocity / 50f).coerceIn(0f, 1f)).coerceIn(0.2f, 0.9f)
}
```

### Effort: 1 day

---

## Valuable 1: Two-Pass LLM Logic (Cost Optimization)

### Problem

Vision API calls are expensive. Sending every chunk to Claude Vision or GPT-4V when accessibility text is already available wastes money.

### Current Code Analysis

| Component | File | Line | Current Behavior |
|-----------|------|------|-----------------|
| Transcription entry | `SyncRepositoryImpl` | (transcription flow) | Always sends chunks as images to vision model regardless of available context |
| Image prep | `StrokeManager.kt` | 173-253 | `toBitmapForOcr()` crops and scales to max 800px — good baseline |
| Context availability | `OverlayService.kt` | 244-260 | Region text and auto-context ARE captured and stored on sessions |
| Prompt template | `PromptTemplateV2.kt` | 5-83 | Already includes context in prompt — but always paired with vision |
| Image encoding | `ClaudeService.kt` / `GeminiService.kt` | (transcribe methods) | Base64 encode images, send to vision endpoint |
| Cost tracking | `UsageTracker` / `LlmCostCalculator` | — | Tracks tokens used but doesn't differentiate vision vs text-only |

### Implementation Plan

#### New class: `TwoPassTranscriptionService.kt` (in `api/`)

```kotlin
class TwoPassTranscriptionService(
    private val textProvider: TranscriptionService,    // Cheap: Gemini Flash text-only
    private val visionProvider: TranscriptionService    // Expensive: Claude Vision / GPT-4V
) : TranscriptionService {
    suspend fun transcribe(chunks, contexts, settings): TranscriptionResult {
        val contextText = contexts
            .filterIsInstance<CapturedContext.SelectedText>().map { it.text } +
            contexts.filterIsInstance<CapturedContext.RegionText>().map { it.text }

        val needsVision = shouldUseVision(chunks, contextText.joinToString("\n"))

        return if (needsVision) {
            visionProvider.transcribe(chunks, contexts, settings)
        } else {
            textOnlyTranscribe(chunks, contextText, settings)
        }
    }

    private fun shouldUseVision(chunks, contextText): Boolean {
        if (contextText.isBlank()) return true   // No text context
        // Analyze stroke complexity for diagram detection
        return chunks.any { isComplexDrawing(it) }
    }
}
```

#### Modifications required

| File | Location | Change |
|------|----------|--------|
| New: `api/TwoPassTranscriptionService.kt` | — | Decision logic + text-only fallback |
| `LlmProviderFactory.kt` | Provider creation | Add option to create a `TwoPassTranscriptionService` wrapping two providers |
| `AppModule.kt:220-260` | `v2Module` | Wire `TwoPassTranscriptionService` when cost optimization is enabled |
| `ImageProcessor.kt` | `bitmapToWebP()` | Add optional `toGrayscale()` conversion before encoding — handwriting is monochrome, saves ~60% payload |
| Settings DataStore | — | Add `prefer_text_only: Boolean` and `vision_threshold: Float` prefs |
| Cost tracking | `UsageTracker` | Track `costSaved` flag, show user how much they saved |

### Effort: 1 day

---

# PART B: NICE-TO-HAVE FEATURES

Implement after v1.1 ships with real users.

**Priority order:**
1. **Circle to Do** (gesture actions) — highest impact, moderate effort
2. **TL;DR Overlay** (summarization) — high value, low effort
3. **Privacy Shield** (PII blur) — trust builder, moderate effort

---

## Feature 1: Circle to Do

### Concept

Draw a circle around screen content → app detects intent → executes action. No writing required.

### User Flow

```
User reading article with a date mentioned
  → Opens overlay
  → Circles "January 15th at 3pm"
  → Synapse detects: CALENDAR intent
  → Shows: "Add to calendar?" [Yes] [No]
  → User taps Yes → Calendar app opens
```

### Supported Intents

| Circle Content | Detected Intent | Action |
|----------------|-----------------|--------|
| Date/time | CALENDAR | Open calendar with event |
| Math expression | CALCULATE | Show result inline |
| Foreign text | TRANSLATE | Show translation overlay |
| Phone number | CALL | Open dialer |
| Address | MAPS | Open maps |
| URL | OPEN | Open browser |
| Unknown | SEARCH | Web search |

### Current Code to Build On

| Component | File | Line | Reuse Opportunity |
|-----------|------|------|-------------------|
| Region gesture | `RegionGestureDetector.kt` | 1-79 | Extend to detect closed shapes (circle) vs rectangle drag |
| Region capture | `OverlayService.kt` | 229-316 | `handleRegionSelected()` already extracts text from region via accessibility → reuse for circle bounds |
| Intent types | `IntentType.kt` | 1-34 | Extend enum with CALENDAR, CALCULATE, CALL, MAPS, TRANSLATE |
| Reminder manager | `ReminderManager.kt` | 1-54 | `createCalendarEvent()` already creates calendar intents |
| Accessibility text | `SynapseAccessibilityService.kt` | 147-179 | `getTextInRegion()` extracts text by bounds — use circle's bounding rect |

### New Files Required

| File | Purpose |
|------|---------|
| `ui/overlay/ShapeGestureDetector.kt` | Detect circles (closed path with >270° rotation) vs rectangles vs underlines vs strokes |
| `service/CircleActionManager.kt` | Orchestrate: extract content → classify intent → confirm → execute |
| `service/IntentClassifier.kt` | Local regex patterns (dates, phones, math, URLs) + LLM fallback for ambiguous text |
| `service/ActionExecutor.kt` | Intent dispatch: calendar, dialer, maps, browser, calculator, clipboard |
| `ui/overlay/CircleActionPopup.kt` | Compact confirmation card near circle position |
| `ui/overlay/InlineResultOverlay.kt` | For CALCULATE and TRANSLATE — show result near the circle with copy button |

### Key Integration Points

| Existing File | Location | Change |
|---------------|----------|--------|
| `CaptureCanvas.kt:140-167` | Region mode modifier | Add `CIRCLE_ACTION` mode alongside `regionSelectionEnabled` |
| `RegionGestureDetector.kt` | Entire file | Either extend or replace with `ShapeGestureDetector` that returns `DetectedShape` sealed class |
| `OverlayService.kt:1198-1214` | Toolbar buttons | Add circle mode toggle button |
| `OverlayService.kt:760-762` | `onRegionSelected` | Route to `CircleActionManager` when in circle mode |
| `IntentType.kt:3-9` | Enum values | Add: `CALENDAR`, `CALCULATE`, `TRANSLATE`, `CALL`, `MAPS`, `OPEN`, `SEARCH` (or create separate `CircleIntent` enum) |
| `ReminderManager.kt` | Class | Add `openDialer()`, `openMaps()`, `openUrl()`, `webSearch()` convenience methods |
| `AppModule.kt:220-260` | `v2Module` | Register `CircleActionManager`, `IntentClassifier`, `ActionExecutor` |

### Dependencies

```kotlin
// Math evaluation (for CALCULATE intent)
implementation("net.objecthunter:exp4j:0.4.8")
```

### Effort: 4-5 days

---

## Feature 2: TL;DR Overlay (Smart Summarization)

### Concept

When user views long content, bubble pulses. Tap → instant summary bottom sheet with follow-up questions.

### User Flow

```
User opens long article in Chrome
  → Synapse detects >500 words on screen
  → Bubble pulses gently
  → Tap → bottom sheet with 3-5 bullet summary
  → Can ask follow-up questions
```

### Current Code to Build On

| Component | File | Line | Reuse Opportunity |
|-----------|------|------|-------------------|
| Text extraction | `SynapseAccessibilityService.kt` | 103-121 | `collectAllTextNodes()` already traverses all text nodes |
| Window content events | `SynapseAccessibilityService.kt` | 66-68 | `TYPE_WINDOW_CONTENT_CHANGED` already triggers `updateNodeCache()` |
| LLM providers | `LlmProviderFactory.kt` | — | Route summary request to configured cheap provider |
| Text query | `TranscriptionService.kt` | — | `textQuery()` method already exists |
| Bubble composable | `OverlayService.kt` | 1026-1107 | Extend with pulse animation state |

### New Files Required

| File | Purpose |
|------|---------|
| `ui/overlay/SummarySheet.kt` | ModalBottomSheet composable with summary display + follow-up input |
| `ui/overlay/SummaryViewModel.kt` | Manage summarization state, streaming response, follow-up conversation |

### Key Integration Points

| Existing File | Location | Change |
|---------------|----------|--------|
| `SynapseAccessibilityService.kt:66-68` | `TYPE_WINDOW_CONTENT_CHANGED` handler | Add word count check, broadcast `ACTION_LONG_CONTENT_DETECTED` when >500 words |
| `SynapseAccessibilityService.kt` | New method | Add `extractAllText()` public method returning full page text |
| `OverlayService.kt:1026-1107` | `FloatingBubble` | Add `hasLongContent: Boolean` parameter, add `infiniteTransition` pulse animation (scale 1.0→1.15, glow alpha) |
| `OverlayService.kt:628-654` | `showFloatingBubble()` | Listen for long content broadcasts, pass state to bubble |
| `OverlayService.kt:151-182` | `onCreate()` | Register broadcast receiver for `ACTION_LONG_CONTENT_DETECTED` |

### Effort: 2 days

---

## Feature 3: Privacy Shield (PII Blur)

### Concept

Before sharing/saving screenshots, auto-detect and blur sensitive information.

### Detected PII Types

| Type | Detection | Method |
|------|-----------|--------|
| Email addresses | Regex | `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}` |
| Phone numbers | Regex | `(\+\d{1,3})?[\d\s\-()]{7,}` |
| Credit cards | Regex + Luhn | `\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}` |
| API keys | Regex | `(sk\|pk\|api\|key\|token)[-_]?[a-zA-Z0-9]{20,}` |
| SSN | Regex | `\d{3}[-\s]?\d{2}[-\s]?\d{4}` |

### New Files Required

| File | Purpose |
|------|---------|
| `util/PiiDetector.kt` | Regex-based PII detection returning `List<PiiMatch>` with type, text, and range |
| `util/PiiBlurrer.kt` | Pixelate bitmap regions corresponding to detected PII |

### Integration Points

| File | Location | Change |
|------|----------|--------|
| `ImageProcessor.kt` | Before save/share | Optional PII scan + blur pass |
| `OverlayService.kt:318-332` | `saveScreenshot()` | Run `PiiDetector` on extracted text, get regions, apply `PiiBlurrer` to bitmap |
| Settings DataStore | — | `auto_blur_pii: Boolean` preference |

### Dependencies

```kotlin
// Optional: ML Kit for on-device PII detection (more accurate than regex)
implementation("com.google.mlkit:text-recognition:16.0.0")
```

### Effort: 2.5 days

---

# Implementation Roadmap

## Before Launch (Critical) — 2.5 days total

| # | Feature | Effort | Files Modified | New Files |
|---|---------|--------|---------------|-----------|
| C1 | Permission Recovery | 0.5d | OverlayService.kt, PermissionHelper.kt, SynapseAccessibilityService.kt, AppModule.kt | PermissionHealthMonitor.kt, PermissionRecoveryDialog composable |
| C2 | Palm Rejection | 0.5d | CaptureCanvas.kt, OverlayService.kt (TouchDifferentiatingOverlayView) | PalmRejectionFilter.kt |
| C3 | Accessibility Compliance | 0.5d | accessibility_service_config.xml, strings.xml, OverlayService.kt, AndroidManifest.xml | SynapseCapabilities.kt, AccessibilitySettingsActivity.kt, PRIVACY.md |
| C4 | Ink Smoothing | 1.0d | StrokeManager.kt, CaptureCanvas.kt, CaptureViewModel.kt | StrokeSmoother.kt, StrokePoint.kt |

## Post-Launch Phase 1 — 5-6 days

| # | Feature | Effort |
|---|---------|--------|
| V1 | Two-Pass LLM | 1d |
| B1 | Circle to Do | 4-5d |

## Post-Launch Phase 2 — 4.5 days

| # | Feature | Effort |
|---|---------|--------|
| B2 | TL;DR Overlay | 2d |
| B3 | Privacy Shield | 2.5d |

## Dependency Graph

```
C1 Permission Recovery ──┐
C2 Palm Rejection ───────┤──→ v1.0 Launch
C3 Accessibility ────────┤
C4 Ink Smoothing ────────┘
                              │
V1 Two-Pass LLM ─────────────┤──→ v1.1 Cost Optimization
                              │
B1 Circle to Do ──────────────┤──→ v1.2 Gesture Actions
    └── needs: RegionGestureDetector, AccessibilityService, ReminderManager
                              │
B2 TL;DR Overlay ─────────────┤──→ v1.3 Summarization
    └── needs: AccessibilityService, LlmProvider
                              │
B3 Privacy Shield ────────────┘──→ v1.4 Trust & Safety
```

## New Gradle Dependencies

```kotlin
dependencies {
    // Math evaluation for Circle to Do (CALCULATE intent)
    implementation("net.objecthunter:exp4j:0.4.8")

    // Optional: ML Kit for Privacy Shield OCR
    implementation("com.google.mlkit:text-recognition:16.0.0")
}
```
