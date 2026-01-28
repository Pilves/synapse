# Synapse

**Zero-friction handwriting capture for Obsidian.**

Synapse is an Android overlay app that lets you capture handwritten notes without leaving your current app. Scribble quick thoughts, and later sync them to your Obsidian vault as clean, formatted markdown - transcribed by your choice of LLM.

## How It Works

1. **Tap the floating bubble** - appears over any app
2. **Scribble your notes** - on a transparent fullscreen canvas
3. **Auto-chunks after 3s** - keeps capturing as you write
4. **Tap Done** - session ends
5. **Review & Sync** - select project, transcribe via LLM, append to vault

## Features

- **Floating Overlay** - Capture notes without switching apps
- **Automatic Chunking** - 3-second timeout creates natural breaks
- **Multi-Provider LLM** - Gemini, Claude, OpenAI, or local Ollama
- **Smart Transcription** - Messy handwriting → clean markdown
- **Mermaid Diagrams** - Hand-drawn flowcharts → code (with advanced formatting)
- **Obsidian Integration** - Direct sync via Storage Access Framework
- **Offline Support** - Queue syncs for when you're back online

## Requirements

- Android 8.0+ (API 26)
- Stylus recommended (works with finger)
- Obsidian vault on device storage
- API key for cloud LLM (or Ollama for local)

## Installation

### Download
Coming soon - see [Releases](https://github.com/Pilves/synapse/releases)

### Build from Source
```bash
git clone https://github.com/Pilves/synapse.git
cd synapse
# Open in Android Studio
# Build → Run
```

## Setup

1. Grant overlay permission (draw over other apps)
2. Select your Obsidian vault folder
3. Enter API key (Gemini free tier available)
4. Add projects (subfolders in your vault)

---

## Roadmap

### Phase 1: Core Foundation ✅
> *Basic infrastructure and project setup*

- [x] Project structure with Kotlin + Jetpack Compose
- [x] Koin dependency injection setup
- [x] Data models (Chunk, Session, Project, Settings)
- [x] Material 3 theming (light/dark mode)
- [x] Navigation graph and main activity

### Phase 2: Capture System ✅
> *The heart of the app - overlay and drawing*

- [x] Floating bubble service (draggable, always on top)
- [x] Fullscreen transparent overlay canvas
- [x] Stroke capture with stylus/finger support
- [x] 3-second chunk timeout with fade animation
- [x] Undo last stroke functionality
- [x] Session management (15-min auto-end)
- [x] Foreground service with notification

### Phase 3: Storage Layer ✅
> *Reliable persistence of captured data*

- [x] WebP image storage (85% quality)
- [x] Atomic writes (temp file → verify → rename)
- [x] Session metadata persistence (JSON)
- [x] Thumbnail generation for review
- [x] Image stitching for combined view
- [x] Storage space checks (50MB minimum)
- [x] Corrupted file detection and handling

### Phase 4: LLM Integration ✅
> *Multi-provider transcription system*

- [x] Provider abstraction interface
- [x] Gemini service (gemini-1.5-flash)
- [x] Claude service (claude-3-haiku)
- [x] OpenAI service (gpt-4o-mini)
- [x] Ollama service (local llava)
- [x] Prompt template system
- [x] Rate limiting (safe/fast modes)
- [x] Batch processing for large sessions

### Phase 5: Review & Sync ✅
> *User interface for managing captures*

- [x] Review screen with session list
- [x] Stitched vs separate view modes
- [x] Chunk selection and deletion
- [x] Project/file picker for sync target
- [x] Sync progress indicator
- [x] Partial success handling
- [x] Offline queue with auto-retry

### Phase 6: Settings & Configuration ✅
> *Full customization options*

- [x] Capture settings (timeout, fade, auto-end)
- [x] LLM provider selection and API keys
- [x] Cleanup mode toggle
- [x] Advanced formatting toggle (Mermaid, LaTeX)
- [x] Custom prompt template editor
- [x] Project manager (add/edit/delete)
- [x] DataStore persistence

### Phase 7: Onboarding ✅
> *First-run experience*

- [x] Welcome screen
- [x] Overlay permission request
- [x] Vault folder selection
- [x] API key setup
- [x] Permission helper utilities

### Phase 8: Vault Integration ✅
> *Writing to Obsidian*

- [x] SAF (Storage Access Framework) integration
- [x] Persistent URI permissions
- [x] Markdown formatter
- [x] Append to existing files
- [x] Create files if needed

---

### Phase 9: Polish & Testing 🔄
> *Quality assurance and refinements*

- [ ] Unit tests for repositories
- [ ] UI tests for critical flows
- [ ] Edge case handling improvements
- [ ] Performance optimization
- [ ] Memory leak fixes
- [ ] Battery usage optimization
- [ ] Crash reporting integration

### Phase 10: Beta Release 📋
> *Preparing for public use*

- [ ] GitHub Actions CI/CD pipeline
- [ ] Signed APK builds
- [ ] Release workflow automation
- [ ] Privacy policy
- [ ] Contributing guidelines
- [ ] Issue templates
- [ ] Beta testing program

### Phase 11: Enhanced Features 📋
> *Post-launch improvements*

- [ ] Samsung S Pen Air Command integration
- [ ] Pen pressure sensitivity
- [ ] Multiple pen colors (user preference)
- [ ] Adjustable stroke width
- [ ] Palm rejection improvements
- [ ] Widget for quick capture
- [ ] Wear OS companion (stretch goal)

### Phase 12: Community Features 📋
> *Based on user feedback*

- [ ] Custom LLM endpoint support
- [ ] Template library (prompt sharing)
- [ ] Backup/restore settings
- [ ] Statistics dashboard
- [ ] Batch operations in review
- [ ] Search through past sessions

---

## Legend

| Symbol | Status |
|--------|--------|
| ✅ | Completed |
| 🔄 | In Progress |
| 📋 | Planned |

---

## Tech Stack

| Component | Choice |
|-----------|--------|
| Language | Kotlin |
| UI | Jetpack Compose |
| DI | Koin |
| State | ViewModel + StateFlow |
| Storage | DataStore, SAF |
| Network | OkHttp, Ktor |
| Images | Coil, WebP |

## Architecture

```
app/
├── api/           # LLM service implementations
├── data/
│   ├── repository/   # Business logic layer
│   └── storage/      # File operations
├── di/            # Koin modules
├── model/         # Data classes
├── service/       # Overlay & notifications
├── ui/
│   ├── navigation/   # NavGraph, MainScreen
│   ├── onboarding/   # First-run flow
│   ├── overlay/      # Canvas, capture
│   ├── review/       # Session management
│   └── settings/     # Configuration
└── util/          # Helpers
```

## Contributing

Contributions welcome! Please:

1. Check existing issues first
2. Open an issue to discuss major changes
3. Fork → branch → PR
4. Test on a real device

## License

MIT License - see [LICENSE](LICENSE)

---

*Built for the Obsidian community by note-takers who hate context-switching.*
