# Synapse — Roadmap to Production

## Codebase Status

| Metric | Value |
|--------|-------|
| Source files | 91 Kotlin files |
| Test files | 24 unit test files |
| Architecture | Single-module, MVVM, Koin DI, Jetpack Compose |
| Packages | `api`, `data`, `model`, `service`, `ui`, `util`, `di` |
| Completeness | Core pipeline fully working, UX hardening done |

### What's production-ready

- Handwriting capture with overlay service (`OverlayService`, `CaptureOverlayManager`, `FloatingBubbleManager`)
- Region selection (accessibility text + screenshot fallback)
- 4 LLM providers (Claude, OpenAI, Gemini, Ollama) -- fully implemented
- Vault sync pipeline (transcribe -> segment -> write to Obsidian/local via SAF)
- Settings, onboarding, cost tracking
- Firebase Crashlytics, CI/CD with auto-release
- SSL certificate pinning with backup pins (Anthropic, OpenAI, Google)
- Accessibility service Play Store compliance (detailed DO/DON'T description, settingsActivity, capability mode detection)
- Permission health monitoring with broadcast-based reactive updates
- Palm rejection filter (touch area geometry + stylus-active finger suppression)
- Ink smoothing with Catmull-Rom splines and pressure-sensitive variable-width strokes
- Haptic feedback on region selection and confirmation dialogs
- Confirmation dialogs for destructive actions (delete session, delete chunk)
- Offline indicator in UI
- 24 unit tests covering API services, repositories, storage, ViewModels, and utilities

---

# PHASE 0: SHIP BLOCKERS (remaining)

---

## 0.1 Network-Triggered Sync Retry

### Problem

`SyncStorage` queue and `NetworkMonitor` both exist but are not connected. Failed syncs require manual retry.

### Changes

| File | Change |
|------|--------|
| `SyncRepository` | Wire `NetworkMonitor.isConnected` flow to trigger `processQueue()` on connectivity restore |

---

## 0.2 Play Store Requirements

### Problem

Missing standard requirements for publishing.

### Changes

| Item | Action |
|------|--------|
| Privacy policy | `PRIVACY.md` is current -- host on web for Play Store listing |
| Release signing | Generate proper release keystore, secure in CI via secrets |
| Data safety form | Prepare responses (local storage, API key in EncryptedSharedPreferences, image data sent to user-chosen LLM) |
| App listing | Screenshots, description, feature graphic |

---

# PHASE 3: DIFFERENTIATING FEATURES

Post-launch features that make Synapse worth paying for. v1.2+.

---

## 3.1 Circle to Do (Gesture Actions)

### Concept

Draw a circle around screen content -> app detects intent -> executes action.

| Circle Content | Intent | Action |
|----------------|--------|--------|
| Date/time | CALENDAR | Open calendar with event |
| Math expression | CALCULATE | Show result inline |
| Foreign text | TRANSLATE | Show translation overlay |
| Phone number | CALL | Open dialer |
| Address | MAPS | Open maps |
| URL | OPEN | Open browser |
| Unknown | SEARCH | Web search |

### Circle Detection Algorithm

- **Angular accumulation:** sum signed angle changes between consecutive stroke segments; >270 degrees total = circle
- **Closure test:** distance between first and last point < 15% of bounding box diagonal
- **Minimum requirements:** >=12 points, bounding box diagonal >=50px
- **Scribble rejection:** require smooth curvature -- standard deviation of segment angles < threshold (e.g., 0.5 radians)

### Existing Code to Reuse

| Component | File | Reuse |
|-----------|------|-------|
| Region gesture | `RegionGestureDetector.kt` | Extend to detect closed shapes |
| Region capture | `OverlayService.kt` | `handleRegionSelected()` text extraction |
| Accessibility text | `SynapseAccessibilityService.kt` | `getTextInRegion()` |

### New Files

| File | Purpose |
|------|---------|
| `ui/overlay/ShapeGestureDetector.kt` | Detect circles (>270 degrees rotation) vs rectangles vs strokes |
| `service/CircleActionManager.kt` | Extract -> classify -> confirm -> execute |
| `service/IntentClassifier.kt` | Regex patterns + LLM fallback |
| `service/ActionExecutor.kt` | Intent dispatch |
| `ui/overlay/CircleActionPopup.kt` | Confirmation card near circle |
| `ui/overlay/InlineResultOverlay.kt` | Show calc/translate results |

### Key Changes

| File | Change |
|------|--------|
| `CaptureCanvas.kt` | Add `CIRCLE_ACTION` mode |
| `RegionGestureDetector.kt` | Extend with `DetectedShape` sealed class |
| `OverlayService.kt` | Add circle mode toggle, route to `CircleActionManager` |
| `AppModule.kt` | Register new managers |

### Dependencies

```kotlin
implementation("net.objecthunter:exp4j:0.4.8")  // Math evaluation
```

### Testing

- Unit tests for `ShapeGestureDetector` (circle vs rectangle vs stroke vs scribble)
- Unit tests for `IntentClassifier` (regex patterns for each PII type)
- Instrumented tests for gesture detection with synthetic `MotionEvent` sequences

---

## 3.2 TL;DR Overlay (Smart Summarization)

### Concept

Bubble pulses when long content detected on screen. Tap -> instant summary bottom sheet with follow-up questions.

### Existing Code to Reuse

| Component | File | Reuse |
|-----------|------|-------|
| Text extraction | `SynapseAccessibilityService.kt` | `collectAllTextNodes()` |
| Window events | `SynapseAccessibilityService.kt` | `TYPE_WINDOW_CONTENT_CHANGED` |
| LLM | `LlmProvider.kt` | Route to configured cheap provider |
| Text query | `TranscriptionService.kt` | `textQuery()` |
| Bubble | `FloatingBubbleManager.kt` | Extend with pulse animation |

### New Files

| File | Purpose |
|------|---------|
| `ui/overlay/SummarySheet.kt` | ModalBottomSheet with summary + follow-up input |
| `ui/overlay/SummaryViewModel.kt` | Summarization state, streaming response |

### Key Changes

| File | Change |
|------|--------|
| `SynapseAccessibilityService.kt` | Add word count check, broadcast when >500 words; add `extractAllText()` public method |
| `OverlayService.kt` | Add pulse animation to bubble when `hasLongContent == true`; register broadcast receiver |

### Testing

- Unit tests for word count threshold and broadcast logic
- UI test for summary sheet display

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

**Note:** Regex-based PII detection has high false positive rates. This should be **opt-in** rather than default-on.

### New Files

| File | Purpose |
|------|---------|
| `util/PiiDetector.kt` | Regex-based detection returning `List<PiiMatch>` |
| `util/PiiBlurrer.kt` | Pixelate bitmap regions |

### Key Changes

| File | Change |
|------|--------|
| `ImageProcessor.kt` | Optional PII scan + blur pass before save/share |
| `OverlayService.kt` | Run `PiiDetector` on extracted text, apply blur |
| Settings DataStore | Add `auto_blur_pii: Boolean` (default: false) |

### Optional dependency

```kotlin
implementation("com.google.mlkit:text-recognition:16.0.0")  // More accurate than regex
```

### Testing

- Unit tests for `PiiDetector` (each regex pattern, false positive rates)

---

# Implementation Order

```
PHASE 0 -- Ship Blockers (remaining)
|-- 0.1 Network-Triggered Sync Retry
|-- 0.2 Play Store Requirements
         |
         --> v1.0 Launch
         |
PHASE 3 -- Differentiating Features       --> v1.2+
|-- 3.1 Circle to Do
|-- 3.2 TL;DR Overlay
|-- 3.3 Privacy Shield
```

## Dependencies

```kotlin
// Phase 3 only
implementation("net.objecthunter:exp4j:0.4.8")          // Circle to Do: math eval
implementation("com.google.mlkit:text-recognition:16.0.0") // Privacy Shield: OCR (optional)
```
