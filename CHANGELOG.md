# Changelog

All notable changes to Synapse are documented here.

## [Unreleased]

### Added
- **Haptic feedback** -- `HapticFeedbackHelper` provides vibration feedback on region selection and confirmation dialogs
- **Confirmation dialogs** -- delete session and delete chunk actions now require confirmation
- **Offline indicator** -- network status shown in UI
- **Expanded test suite** -- 24 unit test files covering API services, repositories, storage, ViewModels, and utilities
- **Context capture** -- select text in any app and share to Synapse via Android text processing; auto-capture active app info via accessibility service
- **Region capture** -- hold-and-drag gesture to select screen regions; extract text via accessibility or screenshot via MediaProjection
- **Multi-provider LLM routing** -- configure separate providers for transcription (image-based) vs. question answering (text-based)
- **Cost tracking** -- token-based cost estimation before sync, cumulative cost display in review screen
- **Vision query support** -- text-based query endpoint added to all LLM providers (Gemini, Claude, OpenAI, Ollama)
- **Session management** -- queue processing with retry for capture sessions
- **Rate limiting** -- request rate management for LLM API calls
- **Onboarding improvements** -- accessibility permission step, destination setup step, vault project sync during folder selection
- **Review screen enhancements** -- context section, cost display, sync status indicator
- **Settings screen enhancements** -- LLM settings section for multi-provider configuration
- **Screen capture permission flow** -- MediaProjection permission request dialog in MainActivity
- **Firebase Crashlytics** -- crash reporting integration
- **GitHub Actions CI/CD pipeline** -- automated build and test workflow (triggers on push, PR, and manual dispatch)
- **In-memory LRU session cache** -- session cache for improved performance
- **JSON schema versioning** -- storage migration safety via schema version tracking
- **Output sanitization and API key validation utilities** -- input/output validation helpers

### Changed
- License changed from MIT to GPL v3
- OverlayService decomposed into `InputDispatcher`, `OverlaySessionManager`, `CaptureOverlayManager`, and `FloatingBubbleManager`
- Consolidated dual DataStore instances into single store
- Upgraded targetSdk to 35 with dependency updates
- ProGuard rules tightened with log stripping
- Overlay settings eagerly loaded to prevent default-value flash on startup
- LLM parsers now throw `InvalidResponse` consistently instead of returning null

### Security
- Filename sanitization for vault writes (prevents path traversal)
- 30-minute TTL on MediaProjection token
- 5MB response body size limit for LLM API responses
- Accessibility service scoped with sensitive app exclusions (banking, password managers, payment apps)
- Network security config enforces HTTPS-only (except localhost for Ollama)

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
- Compilation errors in ChunkRepository and AppModule
- ReviewViewModel.refreshCostEstimate now logs exceptions instead of swallowing them

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
