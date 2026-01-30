# Synapse — Roadmap to Production

## Codebase Status

| Metric | Value |
|--------|-------|
| Source files | ~110 Kotlin files, ~23k LOC |
| Architecture | Single-module, MVVM, Koin DI, Jetpack Compose |
| Packages | `api`, `data`, `model`, `service`, `ui`, `util`, `di` |
| Completeness | ~97% — full pipeline working, UX hardening done, cost optimization done |

### What's production-ready

- Handwriting capture with overlay service
- Region selection (accessibility text + screenshot fallback)
- 4 LLM providers (Claude, OpenAI, Gemini, Ollama) — fully implemented
- Vault sync pipeline (transcribe → write to Obsidian/local)
- Settings, onboarding, cost tracking
- Firebase Crashlytics, CI/CD with auto-release
- SSL certificate pinning with backup pins (Anthropic, OpenAI, Google)
- Accessibility service Play Store compliance (detailed DO/DON'T description, settingsActivity, capability mode detection)
- Permission health monitoring with self-healing UI (broadcast-based, not polling)
- Palm rejection filter (touch area geometry + stylus-active finger suppression)
- Ink smoothing with Catmull-Rom splines and pressure-sensitive variable-width strokes
- Two-pass LLM cost optimization (text-only bypass when accessibility context is sufficient)
- Grayscale preprocessing for handwriting images (~60% payload reduction)
- Unit tests for StrokeSmoother, PalmRejectionFilter, TwoPassTranscriptionService

---

# PHASE 0: SHIP BLOCKERS (remaining)

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

### Circle Detection Algorithm

- **Angular accumulation:** sum signed angle changes between consecutive stroke segments; >270° total = circle
- **Closure test:** distance between first and last point < 15% of bounding box diagonal
- **Minimum requirements:** ≥12 points, bounding box diagonal ≥50px
- **Scribble rejection:** require smooth curvature — standard deviation of segment angles < threshold (e.g., 0.5 radians)

### Existing Code to Reuse

| Component | File | Reuse |
|-----------|------|-------|
| Region gesture | `RegionGestureDetector.kt` | Extend to detect closed shapes |
| Region capture | `OverlayService.kt` | `handleRegionSelected()` text extraction |
| Intent types | `IntentType.kt` | Extend enum |
| Calendar | `ReminderManager.kt` | `createCalendarEvent()` |
| Accessibility text | `SynapseAccessibilityService.kt` | `getTextInRegion()` |

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
| `CaptureCanvas.kt` | Add `CIRCLE_ACTION` mode |
| `RegionGestureDetector.kt` | Extend with `DetectedShape` sealed class |
| `OverlayService.kt` | Add circle mode toggle, route to `CircleActionManager` |
| `IntentType.kt` | Add CALENDAR, CALCULATE, TRANSLATE, CALL, MAPS, OPEN, SEARCH |
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

Bubble pulses when long content detected on screen. Tap → instant summary bottom sheet with follow-up questions.

### Existing Code to Reuse

| Component | File | Reuse |
|-----------|------|-------|
| Text extraction | `SynapseAccessibilityService.kt` | `collectAllTextNodes()` |
| Window events | `SynapseAccessibilityService.kt` | `TYPE_WINDOW_CONTENT_CHANGED` |
| LLM | `LlmProviderFactory.kt` | Route to configured cheap provider |
| Text query | `TranscriptionService.kt` | `textQuery()` |
| Bubble | `OverlayService.kt` | Extend with pulse animation |

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

# REMAINING ITEMS (not in a phase)

## Persistent Retry/Sync Queue

`RetryHelper.kt` handles transient HTTP failures with exponential backoff, but if the process dies mid-sync, work is lost.

### Changes

| File | Change |
|------|--------|
| New or extend `SyncStorage` | Persistent work queue (Room or WorkManager) for failed sync operations |
| `SyncRepositoryImpl` | Retry on next app launch or connectivity change |
| `NetworkMonitor` | Trigger queue processing on connectivity restored |

## Error Recovery UI

### Changes

| File | Change |
|------|--------|
| `FloatingBubble` | Add red badge for sync failure (distinct from amber permission warning) |
| `ReviewScreen.kt` | Add retry action for failed syncs |

## Destination Picker

`ReviewScreen.kt` has `onAddDestination = { /* TODO: Open destination picker */ }` — needs implementation.

---

# Implementation Order

```
PHASE 0 — Ship Blockers (remaining)
└── 0.3 Play Store Requirements
         │
         ──→ v1.0 Launch
         │
PHASE 3 — Differentiating Features       ──→ v1.2+
├── 3.1 Circle to Do
├── 3.2 TL;DR Overlay
└── 3.3 Privacy Shield
```

## Dependencies

```kotlin
// Phase 3 only
implementation("net.objecthunter:exp4j:0.4.8")          // Circle to Do: math eval
implementation("com.google.mlkit:text-recognition:16.0.0") // Privacy Shield: OCR (optional)
```
