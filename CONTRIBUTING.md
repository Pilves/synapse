# Contributing to Synapse

Thanks for your interest in contributing to Synapse. This guide covers the process for reporting bugs, requesting features, and submitting code changes.

## Bug Reports

Open a [GitHub issue](https://github.com/Pilves/synapse/issues) with:

- **Device model and Android version** (e.g., Samsung Galaxy Tab S9 FE, Android 14)
- **Steps to reproduce** -- numbered list, as specific as possible
- **Expected vs actual behavior**
- **Screenshots or screen recordings** if the issue is UI-related
- **Logcat output** if available (`adb logcat -s Synapse`)

Overlay and accessibility features can behave differently across OEMs, so device info is important.

## Feature Requests

1. Search [existing issues](https://github.com/Pilves/synapse/issues) first to avoid duplicates
2. Open an issue describing the feature, the problem it solves, and how you'd expect it to work
3. Wait for discussion before starting implementation -- scope alignment saves effort

## Pull Requests

1. Fork the repository
2. Create a feature branch from `main` (`git checkout -b feature/your-feature`)
3. Make your changes
4. Test on a real device -- emulators cannot test overlay, accessibility, or MediaProjection features
5. Submit a PR against `main`

### PR Guidelines

- Keep PRs focused -- one feature or fix per PR
- Write a clear description of what changed and why
- Reference related issues with `Closes #123` or `Fixes #123`
- Don't include unrelated formatting changes or refactors

## Local Development

### Prerequisites

- Android Studio Meerkat (2025.1) or newer (required for AGP 9.0)
- JDK 17
- An Android device running 8.0+ (API 26+) with USB debugging enabled
- A stylus is recommended but not required

### Setup

```bash
git clone https://github.com/Pilves/synapse.git
cd synapse
```

Open the project in Android Studio, let Gradle sync, and run on your device.

### Building from CLI

```bash
# Debug build
# Linux/macOS
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug

# APK output
# app/build/outputs/apk/debug/app-debug.apk
```

### Testing Overlay & Accessibility

These features require a physical device:

1. **Overlay** -- grant "Draw over other apps" permission in system settings
2. **Accessibility** -- enable "Synapse" in Settings > Accessibility
3. **Screen capture** -- approve the MediaProjection prompt when using region capture

## Testing

Before submitting a PR, run the following locally:

```bash
# Unit tests
./gradlew test

# Lint checks
./gradlew lint
```

Unit tests cover API services, repositories, storage managers, ViewModels, and utilities. CI runs these automatically on push to `main` and on pull requests.

For overlay, accessibility, and MediaProjection features, manual testing on a physical device is required -- emulators do not support these.

## Code Review

- All PRs require at least one approving review before merge
- The maintainer will review PRs in the order they are submitted
- Address review comments by pushing new commits (don't force-push during review)
- Once approved, the maintainer will merge the PR

## Code Style

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use Jetpack Compose best practices for UI code
- Keep composables small and focused
- Prefer `StateFlow` over `LiveData` for state management
- Use Koin for dependency injection -- register new classes in `di/AppModule.kt`

## Project Structure

```
app/src/main/java/com/synapse/
├── api/          # LLM provider implementations (4 providers + base service + factory)
├── data/         # Repositories, storage, cost tracking
├── di/           # Koin dependency injection modules
├── model/        # Data classes and sealed classes
├── service/      # Android services (overlay, accessibility) + helpers
├── ui/           # Jetpack Compose screens and components
└── util/         # Helpers and utilities
```

## Questions

If you're unsure about anything, open an issue or start a discussion. It's better to ask than to build something that doesn't fit.
