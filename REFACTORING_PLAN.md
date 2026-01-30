# Synapse — Roadmap to Production

## Codebase Status

| Metric | Value |
|--------|-------|
| Source files | ~101 Kotlin files, ~21k LOC |
| Architecture | Single-module, MVVM, Koin DI, Jetpack Compose |
| Packages | `api`, `data`, `model`, `service`, `ui`, `util`, `di` |
| Completeness | ~95% — full capture → LLM → vault pipeline working |

### What's already production-ready

- Handwriting capture with overlay service
- Region selection (accessibility text + screenshot fallback)
- 4 LLM providers (Claude, OpenAI, Gemini, Ollama) — fully implemented
- Vault sync pipeline (transcribe → write to Obsidian/local)
- Settings, onboarding, cost tracking
- Firebase Crashlytics, CI/CD with auto-release

---

# PHASE 0: SHIP BLOCKERS

Cannot publish without these. Zero new features — just making what exists safe and compliant.

---

## 0.1 SSL Certificate Pinning

### Problem

Certificate pinning in `AppModule.kt:155-162` uses placeholder hashes. API keys sent over HTTPS are vulnerable to MITM with a compromised CA.

### Changes

| File | Change |
|------|--------|
| `AppModule.kt:155-162` | Replace `"sha256/BBB..."` placeholders with real SHA-256 pins for `api.anthropic.com`, `api.openai.com`, `generativelanguage.googleapis.com` |

Obtain pins via: `openssl s_client -connect api.anthropic.com:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64`

### Effort: 0.25 days

---

## 0.2 Accessibility Service Play Store Compliance

### Problem

Google Play rejects vague accessibility service descriptions. Current config is minimal, missing required fields.

### Current State

| Component | File | Issue |
|-----------|------|-------|
| XML config | `accessibility_service_config.xml` | Missing `summary`, `settingsActivity`, proper `accessibilityFlags` |
| Description | `strings.xml:25` | One-liner — needs DO/DON'T list per Google policy |
| Fallback | — | No degraded mode when accessibility is off |

### Changes

| File | Change |
|------|--------|
| `accessibility_service_config.xml` | Add `summary`, `settingsActivity`, `flagReportViewIds\|flagRetrieveInteractiveWindows` |
| `strings.xml` | Replace description with detailed DO/DON'T text |
| New: `service/SynapseCapabilities.kt` | Mode detection (FULL/BASIC/MINIMAL) based on available permissions |
| New: `ui/settings/AccessibilitySettingsActivity.kt` | Explanation screen (required by `settingsActivity` attribute) |
| `AndroidManifest.xml` | Register `AccessibilitySettingsActivity` |
| `OverlayService.kt:1148-1150` | Check capabilities before enabling region features |

### Effort: 0.5 days

---

## 0.3 Play Store Requirements

### Problem

Missing standard requirements for publishing.

### Changes

| Item | Action |
|------|--------|
| Privacy policy | Update `PRIVACY.md` with accessibility data handling, API key storage, LLM data transmission details |
| Release signing | Generate proper release keystore, secure in CI via secrets |
| Data safety form | Prepare responses (local storage, API key in EncryptedSharedPreferences, image data sent to user-chosen LLM) |
| App listing | Screenshots, description, feature graphic |

### Effort: 0.5 days

---

# PHASE 1: UX HARDENING

Table-stakes quality that users expect. Ship with v1.0.

---

## 1.1 Permission Recovery (Self-Healing)

### Problem

When Android kills the Accessibility Service or MediaProjection token (battery optimization, memory pressure), features break silently. User thinks app is broken.

### Current State

| Component | File | Line | Behavior |
|-----------|------|------|----------|
| Accessibility singleton | `SynapseAccessibilityService.kt` | 17-19 | `instance` set to `null` in `onDestroy()`, no recovery signal |
| Overlay permission | `OverlayService.kt` | 493 | Checked once at `startOverlay()` |
| MediaProjection | `OverlayService.kt` | 512-521 | Stale flag detected only on start |
| Region capture failure | `OverlayService.kt` | 280-282 | Returns null, logs warning, shows "[Screenshot failed]" |
| Permission helper | `PermissionHelper.kt` | 192-197 | Accessibility not included in `checkAllPermissions()` |
| Bubble UI | `OverlayService.kt` | 1026-1107 | No health state, no warning badge |

### Changes

| File | Change |
|------|--------|
| New: `service/PermissionHealthMonitor.kt` | Polls permission state every 5s, exposes `StateFlow<PermissionHealth>` |
| `OverlayService.kt:110-131` | Add `permissionHealthMonitor` field |
| `OverlayService.kt:151-182` | Start monitoring in `onCreate()`, collect health state |
| `OverlayService.kt:1026-1107` | Add `showWarning: Boolean` + amber badge to `FloatingBubble` |
| `OverlayService.kt:628-654` | Pass health state to bubble, wire warning click to recovery dialog |
| `PermissionHelper.kt:192-197` | Add accessibility to `checkAllPermissions()` |
| `PermissionHelper.kt` | Add `hasAccessibilityPermission()`, `getAccessibilitySettingsIntent()` |
| `SynapseAccessibilityService.kt:40-51` | Broadcast health state on connect |
| `SynapseAccessibilityService.kt:217-219` | Broadcast on disconnect |
| `AppModule.kt:220-260` | Register `PermissionHealthMonitor` |
| New composable: `PermissionRecoveryDialog` | AlertDialog with "Context capture was disabled. Tap Fix to re-enable." |
| `OverlayService.kt:1198-1214` | Gray out region button when no permissions, show tooltip |

### Effort: 0.5 days

---

## 1.2 Palm Rejection

### Problem

Palm rests on tablet while writing → garbage strokes. `BOTH_WRITE` mode has zero palm filtering.

### Current State

| Component | File | Line | Behavior |
|-----------|------|------|----------|
| Input differentiation | `CaptureCanvas.kt` | 170-224 | Binary stylus/touch check, no geometry |
| Touch dispatch | `OverlayService.kt` | 953-975 | Checks `TOOL_TYPE_STYLUS` vs finger, no area check |
| Input mode | `CaptureCanvas.kt` | 175-185 | `BOTH_WRITE` accepts any stylus or finger — palm accepted |

### Changes

| File | Change |
|------|--------|
| New: `ui/overlay/PalmRejectionFilter.kt` | Filter by touch major/minor area (>100f/60f = palm), reject finger while stylus active, manufacturer-specific tuning (Samsung S Pen, Huawei M-Pencil) |
| `CaptureCanvas.kt:133-226` | Add `pointerInteropFilter` before `pointerInput` to intercept raw `MotionEvent` |
| `CaptureCanvas.kt:101-111` | Add `palmRejectionEnabled: Boolean = true` parameter |
| `OverlayService.kt:909-920` | Add `PalmRejectionFilter` field to `TouchDifferentiatingOverlayView` |
| `OverlayService.kt:953-975` | Run through `palmFilter.filterEvent()` before `super.dispatchTouchEvent()` |

### Effort: 0.5 days

---

## 1.3 Ink Smoothing & Pressure Sensitivity

### Problem

Fixed-width strokes with basic quadratic bezier feel cheap. No pressure sensitivity.

### Current State

| Component | File | Line | Behavior |
|-----------|------|------|----------|
| Stroke model | `StrokeManager.kt` | 15-18 | `List<Offset>`, fixed width |
| Path creation | `CaptureCanvas.kt` | 318-354 | Quadratic bezier, uniform width |
| Rendering | `CaptureCanvas.kt` | 281-313 | Single `drawPath()`, no per-point width |
| Point capture | `CaptureCanvas.kt` | 199-221 | Only `Offset` (x,y) — no pressure, no timestamp |
| ViewModel | `CaptureViewModel.kt` | 188-242 | Accepts `Offset` only |

### Changes

| File | Change |
|------|--------|
| New: `ui/overlay/StrokeSmoother.kt` | Catmull-Rom spline interpolation, velocity-based width variation (fast=thin, slow=thick) |
| New: `StrokePoint` data class | `position: Offset`, `pressure: Float`, `timestamp: Long` |
| `StrokeManager.kt:15-18` | `points: List<Offset>` → `points: List<StrokePoint>` |
| `CaptureViewModel.kt:92-93` | `MutableStateFlow<List<Offset>>` → `MutableStateFlow<List<StrokePoint>>` |
| `CaptureViewModel.kt:188-242` | Accept `StrokePoint` in `onDrawStart()`, `onDrawMove()`, `onDrawEnd()` |
| `CaptureCanvas.kt:199-221` | Extract `pressure` from `PointerInputChange`, create `StrokePoint` |
| `CaptureCanvas.kt:281-354` | Replace rendering with `drawSmoothStroke()` using `StrokeSmoother` + variable width |
| `StrokeManager.kt:110-253` | Update `toBitmap()` and `toBitmapForOcr()` to use smoothed paths |

### Effort: 1 day

---

# PHASE 2: COST & PERFORMANCE

Reduce LLM costs and improve efficiency. Ship with v1.1.

---

## 2.1 Two-Pass LLM Logic (Cost Optimization)

### Problem

Vision API calls are expensive. Sending every chunk to Claude Vision when accessibility text is already available wastes money.

### Current State

| Component | File | Behavior |
|-----------|------|----------|
| Transcription flow | `SyncRepositoryImpl` | Always sends chunks as images to vision model |
| Context availability | `OverlayService.kt:244-260` | Region text and auto-context ARE captured and stored |
| Prompt template | `PromptTemplateV2.kt:5-83` | Includes context in prompt — but always paired with vision |

### Changes

| File | Change |
|------|--------|
| New: `api/TwoPassTranscriptionService.kt` | Decision logic: use text-only when context available, vision only when needed (blank context or complex drawings) |
| `LlmProviderFactory.kt` | Option to create `TwoPassTranscriptionService` wrapping cheap text + expensive vision provider |
| `AppModule.kt:220-260` | Wire when cost optimization enabled |
| `ImageProcessor.kt` | Add `toGrayscale()` before encoding — handwriting is monochrome, saves ~60% payload |
| Settings DataStore | Add `prefer_text_only: Boolean`, `vision_threshold: Float` |
| `UsageTracker` | Track `costSaved` metric |

### Effort: 1 day

---

# PHASE 3: DIFFERENTIATING FEATURES

Post-launch features that make Synapse worth paying for. v1.2+.

---

## 3.1 Circle to Do (Gesture Actions)

### Concept

Draw a circle around screen content → app detects intent → executes action.

| Circle Content | Intent | Action |
|----------------|--------|--------|
| Date/time | CALENDAR | Open calendar with event |
| Math expression | CALCULATE | Show result inline |
| Foreign text | TRANSLATE | Show translation overlay |
| Phone number | CALL | Open dialer |
| Address | MAPS | Open maps |
| URL | OPEN | Open browser |
| Unknown | SEARCH | Web search |

### Existing Code to Reuse

| Component | File | Reuse |
|-----------|------|-------|
| Region gesture | `RegionGestureDetector.kt` | Extend to detect closed shapes |
| Region capture | `OverlayService.kt:229-316` | `handleRegionSelected()` text extraction |
| Intent types | `IntentType.kt` | Extend enum |
| Calendar | `ReminderManager.kt` | `createCalendarEvent()` |
| Accessibility text | `SynapseAccessibilityService.kt:147-179` | `getTextInRegion()` |

### New Files

| File | Purpose |
|------|---------|
| `ui/overlay/ShapeGestureDetector.kt` | Detect circles (>270° rotation) vs rectangles vs strokes |
| `service/CircleActionManager.kt` | Extract → classify → confirm → execute |
| `service/IntentClassifier.kt` | Regex patterns + LLM fallback |
| `service/ActionExecutor.kt` | Intent dispatch |
| `ui/overlay/CircleActionPopup.kt` | Confirmation card near circle |
| `ui/overlay/InlineResultOverlay.kt` | Show calc/translate results |

### Key Changes

| File | Change |
|------|--------|
| `CaptureCanvas.kt:140-167` | Add `CIRCLE_ACTION` mode |
| `RegionGestureDetector.kt` | Extend with `DetectedShape` sealed class |
| `OverlayService.kt:1198-1214` | Add circle mode toggle |
| `OverlayService.kt:760-762` | Route to `CircleActionManager` in circle mode |
| `IntentType.kt:3-9` | Add CALENDAR, CALCULATE, TRANSLATE, CALL, MAPS, OPEN, SEARCH |
| `AppModule.kt:220-260` | Register new managers |

### Dependencies

```kotlin
implementation("net.objecthunter:exp4j:0.4.8")  // Math evaluation
```

### Effort: 4-5 days

---

## 3.2 TL;DR Overlay (Smart Summarization)

### Concept

Bubble pulses when long content detected on screen. Tap → instant summary bottom sheet with follow-up questions.

### Existing Code to Reuse

| Component | File | Reuse |
|-----------|------|-------|
| Text extraction | `SynapseAccessibilityService.kt:103-121` | `collectAllTextNodes()` |
| Window events | `SynapseAccessibilityService.kt:66-68` | `TYPE_WINDOW_CONTENT_CHANGED` |
| LLM | `LlmProviderFactory.kt` | Route to configured cheap provider |
| Text query | `TranscriptionService.kt` | `textQuery()` |
| Bubble | `OverlayService.kt:1026-1107` | Extend with pulse animation |

### New Files

| File | Purpose |
|------|---------|
| `ui/overlay/SummarySheet.kt` | ModalBottomSheet with summary + follow-up input |
| `ui/overlay/SummaryViewModel.kt` | Summarization state, streaming response |

### Key Changes

| File | Change |
|------|--------|
| `SynapseAccessibilityService.kt:66-68` | Add word count check, broadcast when >500 words |
| `SynapseAccessibilityService.kt` | Add `extractAllText()` public method |
| `OverlayService.kt:1026-1107` | Add pulse animation to bubble when `hasLongContent == true` |
| `OverlayService.kt:151-182` | Register broadcast receiver for long content |

### Effort: 2 days

---

## 3.3 Privacy Shield (PII Blur)

### Concept

Auto-detect and blur sensitive information before sharing/saving screenshots.

| PII Type | Detection |
|----------|-----------|
| Email | Regex |
| Phone numbers | Regex |
| Credit cards | Regex + Luhn |
| API keys | Regex (`sk-`, `pk-`, `api-`, `token-` + 20+ chars) |
| SSN | Regex |

### New Files

| File | Purpose |
|------|---------|
| `util/PiiDetector.kt` | Regex-based detection returning `List<PiiMatch>` |
| `util/PiiBlurrer.kt` | Pixelate bitmap regions |

### Key Changes

| File | Change |
|------|--------|
| `ImageProcessor.kt` | Optional PII scan + blur pass before save/share |
| `OverlayService.kt:318-332` | Run `PiiDetector` on extracted text, apply blur |
| Settings DataStore | Add `auto_blur_pii: Boolean` |

### Optional dependency

```kotlin
implementation("com.google.mlkit:text-recognition:16.0.0")  // More accurate than regex
```

### Effort: 2.5 days

---

# Implementation Order

```
PHASE 0 — Ship Blockers (1.25 days)
├── 0.1 SSL Certificate Pinning
├── 0.2 Accessibility Compliance
└── 0.3 Play Store Requirements
         │
PHASE 1 — UX Hardening (2 days)          ──→ v1.0 Launch
├── 1.1 Permission Recovery
├── 1.2 Palm Rejection
└── 1.3 Ink Smoothing
         │
PHASE 2 — Cost & Performance (1 day)     ──→ v1.1
└── 2.1 Two-Pass LLM
         │
PHASE 3 — Differentiating Features       ──→ v1.2+
├── 3.1 Circle to Do (4-5 days)
├── 3.2 TL;DR Overlay (2 days)
└── 3.3 Privacy Shield (2.5 days)
```

## Dependencies

```kotlin
// Phase 3 only
implementation("net.objecthunter:exp4j:0.4.8")          // Circle to Do: math eval
implementation("com.google.mlkit:text-recognition:16.0.0") // Privacy Shield: OCR (optional)
```
