# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Fossify Voice Recorder — an Android voice recorder app (Kotlin, XML layouts + view binding, no Compose).
Single Gradle module `:app`, `applicationId` `ai.lequipe.lr` (app name "LR"). The Kotlin package and
Gradle `namespace` were deliberately left as `org.fossify.voicerecorder` — source lives under
`org/fossify/voicerecorder`, not `ai/lequipe/lr`. Java 17, minSdk 26, compileSdk 36.

## Commands

Requires a JDK 17 on `PATH` (`JAVA_HOME`). All commands use the Gradle wrapper.

```bash
./gradlew assembleDebug              # build a debug APK
./gradlew installDebug               # build + install on a connected device
./gradlew detekt                     # static analysis (must pass: maxIssues = 0)
./gradlew lint                       # Android lint (release builds are excluded via checkReleaseBuilds = false)
./gradlew detektBaseline             # regenerate app/detekt-baseline.xml
./gradlew build                      # full check + assemble debug/release
```

There are no instrumentation tests (no `src/androidTest`). `app/src/test` has a small set of JVM unit
tests (e.g. `FilenamesTest`), run with `./gradlew testDebugUnitTest`. `bundle exec fastlane android test`
exists via the `fastlane-plugin-fossify` plugin but currently has nothing to run. Don't claim tests
pass — verify changes by running the actual test task and, when behavior matters, on a device.

Both `app/detekt-baseline.xml` and `app/lint-baseline.xml` are pre-existing suppression baselines,
regenerated monthly by the `update-lint-baselines` workflow. New code must be clean without touching
them.

### Style constraints enforced by detekt (`detekt.yml`)

- Max line length **120** (note: `.editorconfig` says 160 — detekt is the one that fails the build).
- `MagicNumber` is active; only `-1, 0, 1, 2, 42, 1000` are free. Existing files work around this with
  `@file:Suppress("MagicNumber")` (see `helpers/Constants.kt`) or named constants.
- `LongMethod` threshold 120, `ReturnCount` max 4, `LongParameterList` 10/8.

## Architecture

### Recording pipeline

`RecorderService` (foreground service, `foregroundServiceType="microphone"`) owns all recording state —
duration, status, output file, amplitude timers. The UI never touches a recorder directly.

- **UI → service**: `startService` with an action constant from `helpers/Constants.kt`
  (`GET_RECORDER_INFO`, `TOGGLE_PAUSE`, `CANCEL_RECORDING`, `STOP_AMPLITUDE_UPDATE`); a plain
  `startService` with no action starts a recording. The service is not bound (`onBind` returns null).
- **Service → UI**: greenrobot **EventBus**, posting the classes in `models/Events.kt`
  (`RecordingDuration`, `RecordingStatus`, `RecordingAmplitude`, `RecordingCompleted`, `RecordingSaved`).
  Subscribers register in `onAttachedToWindow`/`onCreate` and unregister in `onDestroy`.
- `RecorderService.isRunning` is a static flag read by the UI and the widget.

Encoding goes through `MediaRecorderWrapper`, the sole `Recorder` implementation, producing m4a/AAC or
ogg/Opus via `MediaRecorder` depending on `config.extension`. The mp3/AndroidLame (`Mp3Recorder`,
raw `AudioRecord` PCM + `TAndroidLame`) path has been removed.

### Storage: three code paths by SDK level

This is the main source of complexity and bugs. `Context.kt` / `Activity.kt` in `extensions/` branch on
`isRPlus()`:

- **Android 11+**: SAF only. The app asks for a tree URI over the recordings folder
  (`ensureStoragePermission` → `StoragePermissionDialog` → folder picker) and uses
  `createDocumentUriUsingFirstParentTreeUri` / `DocumentsContract` / `DocumentFile` for all reads,
  writes, moves, and deletes.
- **Pre-11 on SD card**: `getDocumentFile(...)?.createFile(...)`.
- **Pre-11 internal**: direct `java.io.File` plus `FileProvider` for the result URI.

**`Recording.path` is a content URI string on R+ and a filesystem path below R.** Anything consuming
`path` must go through the same `isRPlus()` branch or it will break on one of the two.

### Recycle bin

Not MediaStore's trash — it's a hidden `.trash` subfolder inside the user's recordings folder
(`Context.trashFolder`). Trash/restore are folder moves (`moveRecordings`). The recycle bin is always
on (no `useRecycleBin` setting); deleting a recording always trashes it first. Expired items are purged
after a month by `deleteExpiredTrashedRecordings`, called once per day from `MainActivity`.

### UI structure — "fragments" that are not Fragments

`MainActivity` hosts a `ViewPager` with a plain `PagerAdapter` (`ViewPagerAdapter`). Each page is a
**custom `ConstraintLayout`** subclassing `MyViewPagerFragment` (`RecordingsFragment`, `TrashFragment`),
inflated from `R.layout.fragment_*`. Consequences:

- No Fragment lifecycle. `MainActivity` manually forwards `onResume`/`onDestroy` through the adapter,
  and the views use `onFinishInflate` (bind view binding) and `onAttachedToWindow` (register EventBus).
- Adding a page means editing `ViewPagerAdapter.instantiateItem`, `getCount`, and the tab setup in
  `MainActivity.setupViewPager`. The pager is a fixed 2-tab pager (`getCount()` always `2`).
- Page indices are hardcoded (0 recordings, 1 trash) in the adapter and in search/actmode forwarding.

`RecordingsFragment` combines the recorder controls and the recordings list (recorder + player merged
into one screen), with inline per-row play/pause via `RecordingsAdapter.updateCurrentRecording`.
Playback is a directly-managed `MediaPlayer` owned by `RecordingsFragment`.

### Other entry points

- `SplashActivity` → `MainActivity`. Single launcher icon (no themed-icon activity-aliases).
- Home-screen widget: `MyWidgetRecordDisplayProvider` + `BackgroundRecordActivity`, an invisible
  activity (`AppTheme.NoDisplay`) that exists only to request the notification permission before
  toggling the service, then `moveTaskToBack`.

### Fossify Commons

`org.fossify:commons` supplies the base activities (`BaseSimpleActivity`), `BaseConfig`, the
`FossifyApp` application class, dialogs, themes, a large extension library, and many shared strings
(`org.fossify.commons.R.string.*`). Prefer reusing commons over reimplementing; several local
extensions carry "move to commons in the future" notes.

Commons is **excluded from Dependabot** and bumped only by the `update-commons` workflow — don't
hand-edit `commons` in `gradle/libs.versions.toml` unless that's the actual task.

### Config

`helpers/Config.kt` extends commons' `BaseConfig`; every preference is a `SharedPreferences`-backed
property whose key is a constant in `Constants.kt`. `context.config` is the accessor extension.
Adding a setting = key in `Constants.kt` + property in `Config.kt` + row in `SettingsActivity`.

## Build variants

Single build variant (no product flavors). Debug builds get `applicationIdSuffix = ".debug"`. Release
is minified + resource-shrunk and signed from either `keystore.properties` (see
`keystore.properties_sample`) or `SIGNING_*` env vars; unsigned if neither is present.

## Repo conventions

- **Commits**: Conventional Commits — `feat:`, `fix:`, `chore(deps):`, `chore(l10n):`.
- **Versioning**: `VERSION_NAME` / `VERSION_CODE` / `APP_ID` live in `gradle.properties`.
- **CHANGELOG.md**: Keep a Changelog format. Add user-facing changes under `## [Unreleased]`, and
  reference issues with the link-reference style (`([#273])` plus a `[#273]: <url>` entry at the
  bottom). Pushing a CHANGELOG change to `main` triggers the release-PR workflow.
- **`.fossify/release-marker.txt`** is auto-generated and pushing it triggers a Play Store release.
  Never edit it.
- **Translations**: `app/src/main/res/values-*/` are maintained by translators and automation. Only edit
  `values/strings.xml`; leave the localized copies alone.
- All CI is delegated to shared reusable workflows in `FossifyOrg/.github` — the files under
  `.github/workflows/` are thin wrappers, so the actual steps aren't visible in this repo.
