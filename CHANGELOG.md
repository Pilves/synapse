# Changelog

All notable changes to Synapse are documented here.

## [Unreleased]

### Added
- **Context capture** -- select text in any app and share to Synapse via Android text processing; auto-capture active app info via accessibility service
- **Region capture** -- hold-and-drag gesture to select screen regions; extract text via accessibility or screenshot via MediaProjection
- **Multi-provider LLM routing** -- configure separate providers for transcription (image-based) vs. question answering (text-based)
- **Cost tracking** -- token-based cost estimation before sync, cumulative cost display in review screen
- **Vision query support** -- text-based query endpoint added to all LLM providers (Gemini, Claude, OpenAI, Ollama)
- **Session management** -- queue processing with retry for capture sessions
- **Rate limiting** -- request rate management for LLM API calls
- **Onboarding improvements** -- accessibility permission step, destination setup step, vault project sync during folder selection
- **Review screen enhancements** -- context section, cost display, destination selector, sync status indicator
- **Settings screen enhancements** -- LLM settings section for multi-provider configuration
- **Screen capture permission flow** -- MediaProjection permission request dialog in MainActivity
- **Output formatting** -- context-aware markdown formatter with source attribution
- **Firebase Crashlytics** -- crash reporting integration
- **GitHub Actions CI/CD pipeline** -- automated build and test workflow
- **In-memory LRU session cache** -- session cache for improved performance
- **JSON schema versioning** -- storage migration safety via schema version tracking
- **Output sanitization and API key validation utilities** -- input/output validation helpers
- **Unit test suite** -- tests for cost calculator, output sanitizer, API key validator, stroke smoother, palm rejection filter

### Changed
- License changed from MIT to GPL v3
- OverlayService decomposed into InputDispatcher, OverlaySessionManager, and OverlayUiHost
- Consolidated dual DataStore instances into single store
- Upgraded targetSdk to 35 with dependency updates
- ProGuard rules tightened with log stripping

### Security
- Filename sanitization for vault writes (prevents path traversal)
- 30-minute TTL on MediaProjection token
- 5MB response body size limit for LLM API responses
- Accessibility service scoped with sensitive app exclusions (banking, password managers, payment apps)
- Network security config enforces HTTPS-only (except localhost for Ollama)

### Scaffolded (not yet active)
- **Multi-destination sync** -- destination code exists (clipboard, share sheet, local folder) but sync flow writes only to project files; destination routing not yet invoked
- **Intent detection** -- models, V2 prompt template, and confirmation UI defined but sync flow uses V1 prompt; intent classification not invoked
- **Offline sync queue auto-retry** -- queue with status tracking exists but automatic background processing and network-triggered retry are not wired
- **Usage tracking** -- `UsageTracker` with DataStore persistence exists but is not called from the sync flow after completion
- **Intent type model defined** -- IntentType enum with NOTE, TASK, QUESTION, REMINDER types defined but not used by sync flow

### Fixed
- Stale MediaProjection causing screenshot failures after app restart
- Screenshot capture reliability
- Sync progress tracking
- Onboarding pager page index correction for destination and API key views
- LLM config initialization bug
- Race condition causing sessions not to appear in ReviewScreen
- Duplicate chunk keys in LazyRow causing crash
- Generic error messages replaced with actual transcription/sync errors
- ChunkStorage mutex memory leak
- Session ID generation changed to UUID for uniqueness
- Force-unwrap operators replaced with safe null handling
- API-level-safe WebP compression for API 26+
- Receiver registered with RECEIVER_NOT_EXPORTED flag
- Sessions not loading from disk on startup

## [1.0.0] - 2025-01-01

Initial release.

### Added
- **Floating overlay** -- capture notes without leaving your current app
- **Transparent canvas** -- fullscreen stylus/finger drawing surface
- **Automatic chunking** -- 1-second default inactivity timeout saves strokes as WebP chunks (configurable 1-10s)
- **Session management** -- group chunks into sessions with timestamps
- **Obsidian vault sync** -- append transcribed notes as markdown via SAF
- **LLM transcription** -- handwriting to text via Gemini, Claude, OpenAI, or Ollama
- **Mermaid diagram support** -- hand-drawn flowcharts converted to Mermaid code (advanced formatting mode)
- **LaTeX support** -- handwritten equations converted to LaTeX (advanced formatting mode)
- **Undo support** -- undo last stroke during capture
- **Review screen** -- view sessions, chunk thumbnails, select project, sync
- **Settings screen** -- configure capture timeout, fade animation, auto-end timer, LLM provider, API key, prompt template, vault path
- **Onboarding flow** -- overlay permission, vault selection, API key setup
- **Project management** -- create projects mapped to vault subfolders
- **Custom prompt editor** -- edit the LLM transcription prompt
- **Floating toolbar** -- done, undo, discard controls during capture
- **Draggable bubble** -- user-positionable floating trigger with drag-to-dismiss
- **Foreground service** -- persistent overlay with notification
- **Atomic writes** -- temp file + rename for corruption prevention
- **Auto-detect vault folders** -- scan vault root for project subfolders

### Fixed
- Overlay touch handling for stylus input
- Session creation errors
- Crash on Done (animator on detached view)
- Sync running on main thread
- Ripple crash when closing overlay
- Bubble tap gesture conflicts with long-press
- Drag-to-dismiss Y position tracking
