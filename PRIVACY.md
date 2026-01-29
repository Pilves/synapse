# Privacy Policy

**Last updated:** January 2026

Synapse is a local-first Android app. Your data stays on your device unless you explicitly sync it or send it to an LLM provider for transcription.

## What Synapse Accesses

### Handwriting Data
- Stylus/finger strokes are captured as WebP images stored in app-private cache
- Images are only sent to your chosen LLM provider when you tap "Sync"
- Chunks are deleted from cache after successful sync

### Accessibility Service
- When enabled, Synapse reads on-screen text to provide context for your notes
- Captured context includes: active app name, browser URL (if applicable), page title, and selected text
- This data is stored locally in the session and **only sent to the LLM if you choose to sync that session**
- You can delete any captured context before syncing
- The accessibility service does not log, record, or transmit screen content in the background

### Screen Capture (MediaProjection)
- Used only for region capture -- when you hold-and-drag to select a screen area
- The screenshot is cropped to the selected region, stored temporarily in app cache, and sent to the LLM during sync
- Screen capture requires explicit permission each time the app restarts
- Synapse does not continuously record or stream your screen

### Network Access
- Used exclusively for LLM API calls (transcription and question answering)
- Network connectivity state is monitored to manage the offline sync queue
- No analytics, telemetry, or tracking requests are made

### Storage Access (SAF)
- Used to read and write files in your chosen Obsidian vault or local folder
- Access is scoped to the specific folder you select -- Synapse cannot access other files on your device
- URI permissions persist across app restarts so you don't have to re-select your vault

## What Synapse Does NOT Do

- Does not collect analytics or telemetry
- Does not display ads
- Does not share data with third parties (beyond the LLM provider you configure)
- Does not access contacts, location, camera, or microphone
- Does not run in the background when you're not actively using it (beyond the foreground service notification during capture)

## LLM Providers

When you sync, your handwriting images and any captured context are sent to the LLM provider you've configured:

| Provider | Data sent to |
|----------|-------------|
| Gemini | Google AI (api.generativeai.google) |
| Claude | Anthropic (api.anthropic.com) |
| OpenAI | OpenAI (api.openai.com) |
| Ollama | Your local machine (localhost) |

Each provider has its own privacy policy and data retention practices. Using Ollama keeps all processing on-device.

Your API keys are stored locally in Android DataStore (encrypted app preferences) and are never sent anywhere other than the corresponding provider's API.

## Data Retention

- Handwriting chunks: stored in app cache until synced or manually deleted
- Session metadata: stored in app-private DataStore
- Usage statistics (cost tracking): stored locally, never transmitted
- Captured context: stored with the session, deleted when the session is deleted

Uninstalling Synapse removes all locally stored data.

## Changes to This Policy

Updates will be posted in this file and noted in the changelog. For questions, open an issue on [GitHub](https://github.com/Pilves/synapse/issues).
