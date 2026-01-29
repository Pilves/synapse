# Changelog

All notable changes to Synapse are documented here.

## [Unreleased]

### Added
- **Multi-destination sync** -- sync to local folders, clipboard, share sheet, or Obsidian vault; choose destinations per session
- **Context capture** -- select text in any app and share to Synapse via Android text processing; auto-capture active app info via accessibility service
- **Region capture** -- hold-and-drag gesture to select screen regions; extract text via accessibility or screenshot via MediaProjection
- **Intent detection** -- LLM classifies notes as plain notes, tasks, questions, or reminders; questions answered inline; reminders create alarms or calendar events
- **Multi-provider LLM routing** -- configure separate providers for transcription (image-based) vs. question answering (text-based)
- **Cost tracking** -- token-based cost estimation before sync, cumulative usage statistics stored in DataStore
- **Offline sync queue** -- failed syncs queued with status tracking (pending, queued, syncing, completed, failed); auto-retry on network restore
- **Vision query support** -- text-based query endpoint added to all LLM providers (Gemini, Claude, OpenAI, Ollama)
- **Multi-action sessions** -- process multiple actions within a single capture session
- **Rate limiting** -- request rate management for LLM API calls
- **Onboarding improvements** -- accessibility permission step, destination setup step, vault project sync during folder selection
- **Review screen enhancements** -- context section, cost display, destination selector, sync status indicator, intent confirmation dialogs
- **Settings screen enhancements** -- LLM settings section for multi-provider configuration
- **Screen capture permission flow** -- MediaProjection permission request dialog in MainActivity
- **Context-aware prompts** -- v2 prompt template with intent detection and context integration
- **Output formatting** -- context-aware markdown formatter with source attribution

### Fixed
- Stale MediaProjection causing screenshot failures after app restart
- Screenshot capture reliability
- Sync progress tracking

## [1.0.0] - 2025

Initial release.

### Added
- **Floating overlay** -- capture notes without leaving your current app
- **Transparent canvas** -- fullscreen stylus/finger drawing surface
- **Automatic chunking** -- 3-second inactivity timeout saves strokes as WebP chunks
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
