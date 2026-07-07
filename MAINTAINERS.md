# Maintainer Notes

Operational knowledge for whoever maintains Synapse: what CI actually does, how
secrets and signing work, lint/test policy, and release conventions. Everything
below was verified against `.github/workflows/android.yml`,
`app/build.gradle.kts`, `gradle/libs.versions.toml`, and `CHANGELOG.md` at the
time of writing.

## CI: `.github/workflows/android.yml`

Triggers: push to `main`, PRs targeting `main`, and manual `workflow_dispatch`.
Two jobs:

### `check` (skipped on `workflow_dispatch`)

1. Checkout, set up JDK 17 (temurin), set up Gradle (cache is read-only on
   non-`main` branches).
2. **Decode `google-services.json`** from the `GOOGLE_SERVICES_JSON` repo
   secret (base64-decoded into `app/google-services.json`). This file is
   **not committed** — it's `.gitignore`d (`google-services.json`, line 60) —
   so a fresh local checkout will fail to build with the Firebase plugin
   applied unless you supply your own or reproduce this step manually.
3. `./gradlew test lint --parallel` — unit tests and lint in one pass.
4. If the event is a `pull_request`: generate Kover coverage reports
   (`koverHtmlReport koverXmlReport`), with `continue-on-error: true` (a
   coverage report failure does not fail the build).
5. Always (on PRs): publish JUnit test results as a check
   (`mikepenz/action-junit-report`).
6. On failure: upload test reports and lint HTML reports as artifacts.
7. On PRs: upload the Kover coverage report as an artifact (best-effort,
   `if-no-files-found: ignore`).

### `build` (runs on every trigger, including `check`'s triggers)

1. Same JDK/Gradle setup and `google-services.json` decode step.
2. `./gradlew assembleDebug`.
3. Only on `workflow_dispatch`: parse `versionName` out of
   `app/build.gradle.kts` with a `grep`/`sed` one-liner, then create a GitHub
   Release via `softprops/action-gh-release@v2` tagged
   `v<versionName>-build.<run_number>`, attaching the debug APK and
   auto-generated release notes.

There is no release-build job — CI only ever builds and ships the **debug**
APK. Signing config for a release build exists in `app/build.gradle.kts`
(`signingConfigs { release { ... } }`, reading `SYNAPSE_KEYSTORE_PATH` /
`SYNAPSE_KEYSTORE_PASSWORD` / `SYNAPSE_KEY_ALIAS` / `SYNAPSE_KEY_PASSWORD` from
environment variables) but nothing in CI invokes `assembleRelease` or supplies
those secrets — release signing is a manual/local step today.

## Secrets

| Secret | Used for |
|---|---|
| `GOOGLE_SERVICES_JSON` | Base64-encoded `google-services.json`, decoded in both CI jobs |

Release keystore credentials (`SYNAPSE_KEYSTORE_PATH`, `SYNAPSE_KEYSTORE_PASSWORD`,
`SYNAPSE_KEY_ALIAS`, `SYNAPSE_KEY_PASSWORD`) are read from environment variables
in `app/build.gradle.kts` but are not wired into any GitHub Actions secret —
there is no CI job that produces a signed release build.

## Lint policy

Configured in `app/build.gradle.kts`:

```kotlin
lint {
    warningsAsErrors = false
    abortOnError = true
    baseline = file("lint-baseline.xml")
}
```

- `abortOnError = true`: any *new* lint error (not already in the baseline)
  fails the build.
- `warningsAsErrors = false`: warnings alone don't fail the build.
- `lint-baseline.xml` grandfathers in pre-existing findings so old issues don't
  block new work. Current baseline issue IDs: `ConstantLocale`,
  `DefaultLocale`, `GradleDependency`, `IconDuplicates`, `IconLauncherShape`,
  `NewerVersionAvailable`, `ObsoleteSdkInt`, `OldTargetApi`, `SwitchIntDef`,
  `TypographyEllipsis`, `UseKtx`, `UseTomlInstead`. Don't add new violations of
  these types either — the baseline suppresses *existing* instances, not the
  category; regenerate the baseline deliberately (`./gradlew updateLintBaseline`,
  or equivalent Android Studio action) rather than growing it to hide new
  issues.

## Tests

- Unit tests live under `app/src/test/`. There are **37 unit test `.kt`
  files** as of this writing — note this contradicts CONTRIBUTING.md, which
  says 24; see "Known doc/code discrepancies" below.
- **Robolectric** is used for JVM-side Android tests
  (`org.robolectric:robolectric:4.16.1`, declared in `app/build.gradle.kts`).
  The SDK is pinned via `app/src/test/resources/robolectric.properties`:
  ```
  application=android.app.Application
  sdk=35
  ```
  All Robolectric tests run against API 35 unless a test overrides it locally.
- Other test dependencies: MockK 1.14.7, kotlinx-coroutines-test 1.10.1,
  Turbine 1.2.1 (Flow testing), AndroidX Arch Core testing 2.2.0.
- `app/src/androidTest/` does not currently contain any Kotlin test files —
  the `androidx.test`/Espresso dependencies are declared in
  `app/build.gradle.kts` but there's nothing to run there yet.
- Run locally: `./gradlew test` (unit tests), `./gradlew lint` (lint). Both run
  together in CI via `./gradlew test lint --parallel`.

## Coverage (Kover)

`org.jetbrains.kotlinx.kover` (version 0.9.1) is applied in
`app/build.gradle.kts`. Configuration excludes `*BuildConfig`, `*.di.*`,
`*.model.*`, `*_Factory`, `*_MembersInjector` from reports, and coverage is
**not** enforced on `check` (`onCheck = false` for both HTML and XML reports)
— it's informational only, generated and uploaded as a PR artifact, with
`continue-on-error: true` in the workflow so a Kover failure never blocks CI.

## Versioning and releases

- `versionName` in `app/build.gradle.kts` is currently `"1.0.0"` and
  `versionCode = 1`; these do not appear to be bumped per release today (the
  release tag encodes uniqueness via the build number instead).
- Release tags follow `v<versionName>-build.<github.run_number>`, created
  automatically on `workflow_dispatch` runs of the `build` job (see CI section
  above). Existing tags in this repo: `v1.0.0-build.{6,13,14,15,17,18,19,22,32}`
  — consistent with CHANGELOG.md's description of "Rolling build releases
  between 2026-01-29 and 2026-02-02."
- `CHANGELOG.md` follows a Keep-a-Changelog-style format with `[Unreleased]` at
  the top and dated version sections below (`Added`/`Changed`/`Fixed`/
  `Security`/`Removed`). Update the `[Unreleased]` section as you land changes
  worth calling out; it becomes the next dated section when a release is cut.
- There is no dedicated release-build CI job; producing a signed release APK is
  a manual step using the `release` signing config and the `SYNAPSE_KEYSTORE_*`
  environment variables.

## Physical device requirement

Overlay (`SYSTEM_ALERT_WINDOW`), the accessibility service, and MediaProjection
region capture **cannot be tested on an emulator** — CONTRIBUTING.md states
this and it's consistent with the platform APIs involved (draw-over-apps
permission behavior, accessibility event delivery, and screen-capture consent
flows are all unreliable or unsupported in emulator images). Any change
touching `service/OverlayService.kt`, `service/SynapseAccessibilityService.kt`,
`service/ScreenshotManager.kt`, `service/MediaProjectionHolder.kt`, or the
overlay UI under `ui/overlay/` needs manual verification on a real device
before merge. CI only compiles and unit-tests this code — it does not exercise
these flows.

## Known doc/code discrepancies

Found while writing this document; not fixed here (out of scope for a docs
pass) — flag for cleanup:

- **Test file count.** CONTRIBUTING.md's Testing section says "The project has
  24 unit test files." The actual count under `app/src/test/` is **37**, which
  matches the number CHANGELOG.md gives for the `1.0.0-build.32` release
  ("Expanded test suite -- 37 unit test files"). CONTRIBUTING.md's number
  looks stale.
