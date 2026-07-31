# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Fossify Voice Recorder — an Android voice recorder app (Kotlin, XML layouts + view binding, no Compose).
Single Gradle module `:app`, package `ai.lequipe.lr`. Java 17, minSdk 26, compileSdk 36.

## Commands

Requires a JDK 17 on `PATH` (`JAVA_HOME`). All commands use the Gradle wrapper.

```bash
./gradlew assembleDebug              # build a debug APK
./gradlew installDebug               # build + install on a connected device
./gradlew detekt                     # static analysis (must pass: maxIssues = 0)
./gradlew lint                       # Android lint (release builds are excluded via checkReleaseBuilds = false)
./gradlew detektBaseline             # regenerate app/detekt-baseline.xml
./gradlew build                      # full check + assemble all flavors
```

There are **no unit or instrumentation tests** in this repo (no `src/test` or `src/androidTest`).
`bundle exec fastlane android test` exists via the `fastlane-plugin-fossify` plugin but currently
has nothing to run. Don't claim tests pass — verify changes by building and, when behavior matters,
running on a device.

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

Encoding sits behind the `Recorder` interface with two implementations picked by `config.extension`:

- `MediaRecorderWrapper` — m4a/AAC and ogg/Opus via `MediaRecorder`.
- `Mp3Recorder` — raw `AudioRecord` PCM read loop on a background thread, encoded with AndroidLame
  (`TAndroidLame`), amplitude computed manually from the PCM buffer.

Valid bitrate/sampling-rate combinations differ per format and are tabulated in `Constants.kt`
(`BITRATES`, `SAMPLING_RATES`, `SAMPLING_RATE_BITRATE_LIMITS`). Settings UI must respect these tables.

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
(`Context.trashFolder`). Trash/restore are folder moves (`moveRecordings`). There is additional
legacy handling for files still carrying MediaStore's `.trashed-<ts>-` filename prefix
(`getMediaStoreTrashedRecordings`, deprecated). Expired items are purged after a month by
`deleteExpiredTrashedRecordings`, called once per day from `MainActivity`.

### UI structure — "fragments" that are not Fragments

`MainActivity` hosts a `ViewPager` with a plain `PagerAdapter` (`ViewPagerAdapter`). Each page is a
**custom `ConstraintLayout`** subclassing `MyViewPagerFragment` (`RecorderFragment`, `PlayerFragment`,
`TrashFragment`), inflated from `R.layout.fragment_*`. Consequences:

- No Fragment lifecycle. `MainActivity` manually forwards `onResume`/`onDestroy` through the adapter,
  and the views use `onFinishInflate` (bind view binding) and `onAttachedToWindow` (register EventBus).
- Adding a page means editing `ViewPagerAdapter.instantiateItem`, `getCount`, and the tab setup in
  `MainActivity.setupViewPager`. The recycle-bin tab is conditional on `config.useRecycleBin`, and the
  pager is rebuilt in `onResume` when that setting changes.
- Page indices are hardcoded (0 recorder, 1 player, 2 trash) in the adapter and in search/actmode
  forwarding.

Playback lives in `PlayerFragment` with a directly-managed `MediaPlayer`, plus a
`BecomingNoisyReceiver` to pause when headphones are unplugged.

### Other entry points

- `SplashActivity` → `MainActivity`. Themed launcher icons are 19 `activity-alias` entries on
  `SplashActivity` toggled by commons' customization screen; the list is mirrored in
  `SimpleActivity.getAppIconIDs()`.
- `MainActivity` also handles the third-party `MediaStore.Audio.Media.RECORD_SOUND_ACTION` intent,
  returning the recording URI via the `Events.RecordingSaved` subscriber.
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

Three flavors on the `variants` dimension, differing only in `res/values/bools.xml`:

- `core` — no overrides (uses commons defaults).
- `foss` — `hide_google_relations = true`, donate link shown.
- `gplay` — Google relations visible, no donate link.

Debug builds get `applicationIdSuffix = ".debug"`. Release is minified + resource-shrunk and signed
from either `keystore.properties` (see `keystore.properties_sample`) or `SIGNING_*` env vars;
unsigned if neither is present.

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
