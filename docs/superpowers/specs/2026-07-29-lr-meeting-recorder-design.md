# LR — Meeting Recorder (design)

**Date:** 2026-07-29
**Status:** Draft, pending user review
**Fork of:** [Fossify Voice Recorder](https://github.com/FossifyOrg/Voice-Recorder) (GPLv3)
**This fork:** https://github.com/aevdokimenko/voice-recorder

## 1. Goal

Strip Fossify Voice Recorder down to a single-purpose meeting recorder: record → optionally
rename → auto-upload to a configured endpoint → delete when no longer needed. Remove every
feature, screen, and setting that doesn't serve this workflow. Add QR-based endpoint enrollment
and background upload with retry.

### Non-goals

- No manual URL entry — QR scan (or a reachable well-known endpoint) is the only way to connect
  a device to an upload endpoint.
- No playback beyond simple inline play/pause — no scrubbing, no waveform, no share sheet.
- No user-configurable audio format, bitrate, sample rate, or microphone mode — these come from
  the server.
- No multi-store branding — single build, not published to F-Droid/Play under the old identity.
- Designing/operating the actual server is out of scope for the Android work; this doc specifies
  the HTTP contract the client needs and recommends ready-made self-hosted building blocks, but
  building the server is a separate effort.

## 2. User flow

1. Fresh install, first launch: app tries a baked-in well-known enrollment URL. If reachable
   (e.g. device is on the trusted network/VPN), it auto-enrolls silently. If not reachable, the
   app shows a **"Scan QR to connect"** screen (camera) instead.
2. Once enrolled, the main **Recordings** screen shows a record button and, below it, the list of
   this device's recordings.
3. Tapping record starts `RecorderService` as today. Stopping it creates a file named from the
   current timestamp; upload to the configured endpoint starts automatically in the background.
4. Each row in the list shows: name (editable via rename, same as today's rename dialog), duration,
   timestamp, an upload status badge (`Uploading` / `Uploaded` / `Failed` with a retry tap), and an
   inline play/pause button.
5. User can delete any recording at any time; deleting always moves it to the **Recycle Bin**
   (never an immediate permanent delete from the main list).
6. An uploaded recording is automatically moved to the Recycle Bin after a server-configured
   number of days (default 15) even if the user never touches it. The Recycle Bin itself
   auto-purges items after a server-configured number of days (default 30). The Recycle Bin has
   its own "Empty recycle bin" action for manually purging everything early.
7. The home-screen widget still starts/stops recording without opening the app.

## 3. Endpoint discovery & enrollment

One HTTP contract serves both the well-known and QR paths:

- **Well-known (auto)**: on startup (while no endpoint is configured), `POST
  {builtInDefaultHost}/api/v1/enroll` with `{ clientId, deviceName }`, no bearer token. Whether
  this succeeds is a server-side policy decision (e.g. accept only from an allow-listed
  internal/VPN IP range) — the client just tries it with a short timeout (~3s) and falls back to
  QR on any failure (timeout, non-2xx, unreachable).
- **QR (fallback/manual)**: the QR code encodes a URL of the form
  `https://host/api/v1/enroll?token=<opaque>`. Scanning it makes the same enroll call, this time
  with `Authorization: Bearer <token>`.
- **Enroll response** (both paths, same shape):
  ```json
  {
    "deviceToken": "opaque-bearer-token-for-this-device",
    "format": { "codec": "aac", "bitrate": 96000, "sampleRate": 44100 },
    "retention": { "daysUntilTrash": 15, "daysUntilPurge": 30 }
  }
  ```
  `deviceToken` and the `format`/`retention` config are cached locally (e.g. in `Config`,
  encrypted or at least not world-readable).
- **Config refresh**: a daily WorkManager periodic job calls `GET /api/v1/config` with the cached
  `deviceToken` to pick up server-side config changes without re-scanning a QR. Same response
  shape as enroll, minus `deviceToken`.
- **Per-upload presign**: after each recording finishes, `POST /api/v1/presign` with the device
  token and `{ filename, contentType, sizeBytes }` returns `{ uploadUrl, expiresAt }` — a
  short-lived presigned PUT URL. The client `PUT`s the raw audio bytes directly to `uploadUrl`
  (no bearer token needed on that request; the presigned URL itself is the time-limited
  authorization).
- `clientId` is a random UUID generated once on first launch and persisted; it identifies the
  device across re-enrollment/config-refresh calls.

## 4. Recording pipeline

Keep `RecorderService` and `MediaRecorderWrapper` (handles both m4a/AAC and ogg/Opus via
`MediaRecorder`). **Drop `Mp3Recorder` and the AndroidLame/`tandroidlame` dependency entirely** —
it's the most fragile code in the app (hand-rolled `AudioRecord` PCM read loop with manual LAME
encoding), and since format is now server-dictated rather than user-picked, there's no reason to
keep three encoder paths for one.

Format/bitrate/sample-rate are read from the cached enrollment config (`§3`) instead of local
`Config` settings; the format-picker, bitrate-picker, sample-rate-picker, and microphone-mode
settings screens are removed along with their backing preferences and the `BITRATES*` /
`SAMPLING_RATES*` / `SAMPLING_RATE_BITRATE_LIMITS*` tables in `Constants.kt` (only the values
needed to build the `MediaRecorder` calls are kept, driven by server config).

**Filenames**: fixed pattern `yyyyMMdd_HHmmss.<ext>` (e.g. `20260729_140000.m4a`). The custom
filename-pattern editor (`FilenamePatternDialog`, `DateTimePatternInfoDialog`) and its backing
preference are removed. Rename-after-recording still works exactly as today (manual rename
dialog on a list item).

## 5. Persistence — sidecar files, no database

The recordings list stays a directory scan, same mechanism as today (`getAllRecordings`,
`isRPlus()`-branched SAF/File listing) — no database is introduced.

Each recording gets a small sidecar file alongside it, e.g. `20260729_140000.m4a.status.json`:

```json
{
  "status": "PENDING | UPLOADING | UPLOADED | FAILED",
  "remoteKey": "...",
  "attempts": 0,
  "lastError": null,
  "uploadedAt": null
}
```

The scan reads audio files and matches each to its sidecar (if present) to populate status; a
recording with no sidecar is treated as not-yet-uploaded (covers the moment right after
recording, before the upload worker has run). Sidecars are filtered out of the "is this an audio
recording" check the same way `.trash`-prefixed legacy files already are today
(`isAudioRecording()`).

Sidecars move with their recording on trash/restore (same folder-move code path,
`moveRecordings`), and are deleted alongside it on permanent purge. This means "uploaded, but
local copy is gone" is simply the state of an item that's sitting in the Recycle Bin with
`status: UPLOADED` — there is no history retained once an item is actually purged, matching the
"no database, no permanent history" requirement while still satisfying "mark the remote copy as
server-only after local delete" for as long as the item exists (including while trashed).

## 6. Upload & retry

New `UploadWorker` (WorkManager, one-time work request), enqueued as soon as a recording
finishes:

1. Write sidecar with `status: PENDING`.
2. Call `/api/v1/presign`, set `status: UPLOADING`.
3. `PUT` the file to the presigned URL.
4. On success: `status: UPLOADED`, record `remoteKey`/`uploadedAt`.
5. On failure: increment `attempts`, `BackoffPolicy.EXPONENTIAL` (initial ~30s, doubling, capped
   around a few hours), retried automatically by WorkManager up to a bounded attempt count, then
   `status: FAILED`.

A `FAILED` row shows a "Retry" affordance in the list that re-enqueues a fresh one-time work
request (fresh backoff sequence). Work is constrained to `NetworkType.CONNECTED` so it doesn't
spin uselessly offline.

If a recording is deleted (trashed) while its upload work is still pending/running, the enqueued
work is cancelled via its unique work name before the move.

## 7. Delete & retention lifecycle

- Recycle bin is **always on** — the `useRecycleBin` toggle and its conditional 2-vs-3-tab pager
  rebuild logic in `MainActivity`/`ViewPagerAdapter` are removed; the pager is a fixed 2 pages
  (Recordings, Recycle Bin).
- "Delete" from the Recordings list always means trash (`trashRecordings`), never an immediate
  permanent delete — same as today's behavior, just no longer optional.
- New scheduled check (extends today's `deleteExpiredTrashedRecordings`, run once a day from
  `MainActivity` as today): any recording with `status: UPLOADED` whose upload finished more than
  `retention.daysUntilTrash` days ago (from the cached server config) gets auto-trashed.
- Recycle Bin auto-purge interval changes from the hardcoded `MONTH_SECONDS` to
  `retention.daysUntilPurge` from the cached server config (default 30, matches today's default).
- **"Empty recycle bin" moves from Settings into the Recycle Bin tab's own toolbar/menu** — it's
  currently a Settings-screen action (`SettingsActivity` → `deleteTrashedRecordings()`), which no
  longer makes sense once Settings is stripped down and the bin is always-on; it belongs with the
  screen it acts on.

## 8. Screens & navigation

- **Recordings** (today's `RecorderFragment` + `PlayerFragment` merged into one screen):
  recorder controls on top, recordings list below with inline play/pause, rename, delete, and
  upload-status/retry per row. `PlayerFragment`'s dedicated tab, seek bar, and share action go
  away; `RecordingsAdapter` gains the status badge/retry affordance.
- **Recycle Bin**: kept as-is (`TrashFragment`, `TrashAdapter`, `moveRecordings`,
  `restoreRecordings`), plus the relocated "Empty recycle bin" action.
- **Scan QR to connect**: new screen, shown on first launch if the well-known check fails, and
  reachable again later from Settings ("Re-scan QR") if the user needs to reconnect/switch
  endpoints.
- **Settings**: trimmed to connected-endpoint status + "Re-scan QR" action, "keep screen on while
  recording" toggle, and a link to About. Format/bitrate/sample-rate/mic-mode pickers, the
  filename pattern editor, and the recycle-bin toggle are all removed.
- **About**: kept (Commons' `AboutActivity`), FAQ trimmed to what's still relevant, with an added
  line: *"LR is based on Fossify Voice Recorder (GPLv3), modified 2026 — source:
  https://github.com/aevdokimenko/voice-recorder"*.

## 9. Removed entirely

- `Mp3Recorder`, the `tandroidlame` dependency, and all mp3-specific bitrate/sample-rate tables
  (§4).
- `FilenamePatternDialog`, `DateTimePatternInfoDialog`, and the filename-pattern preference (§4).
- Third-party `MediaStore.Audio.Media.RECORD_SOUND_ACTION` handling in `MainActivity`
  (`isThirdPartyIntent`, the manifest intent-filter, the `Events.RecordingSaved` third-party
  consumer branch) — not part of the meeting-recorder workflow.
- Legacy MediaStore-trashed-recording compatibility (`getMediaStoreTrashedRecordings`,
  `isTrashedMediaStoreRecording`) — this exists only to migrate recordings trashed by very old
  versions of the original app; LR is a fresh package id with no upgrade path from an existing
  Fossify Voice Recorder install, so this is dead code from day one.
- The 19 themed-launcher `activity-alias` entries and the link to Commons'
  `CustomizationActivity` (color/theme picker) — single fixed launcher icon and theme.
- The `foss`/`gplay` product flavors and the `variants` flavor dimension — single build going
  forward (`debug`/`release` build types only, `.debug` applicationId suffix unchanged).
- The `useRecycleBin` settings toggle (§7).

## 10. Kept as-is

- Home-screen widget (`MyWidgetRecordDisplayProvider`, `BackgroundRecordActivity`,
  `WidgetRecordDisplayConfigureActivity` — the last of these needs a quick look during
  implementation in case any of its config options were tied to the theming being removed
  elsewhere, but functionally it's out of scope for this rework).
- `org.fossify:commons` as a dependency for its permission dialogs, SAF helpers
  (`createDocumentUriUsingFirstParentTreeUri`, `getDocumentFile`, etc.), and `BaseSimpleActivity`
  — we stop using its theming/customization and multi-flavor branding surface, not the library
  itself.
- The three-tier storage handling (SAF on R+, SD-card `DocumentFile`, plain `File` below R) — the
  spec doesn't touch storage location behavior, only what's stored and how it's tracked.

## 11. New Android dependencies & permissions

- **QR scanning**: ZXing via `zxing-android-embedded` — no Google Play Services dependency (a
  hard requirement carried over from the project's original foss-flavor motivation, now applying
  to the single build).
- **Uploads**: `androidx.work:work-runtime-ktx` (WorkManager).
- **New permissions**: `INTERNET` (the app has never made a network call before this) and
  `CAMERA` (QR scanning). Existing permissions (`RECORD_AUDIO`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE*`) are unchanged.

## 12. Server (recommendation, not part of this build)

Self-hosted, ready components rather than custom-built storage:

- **Recommended**: [MinIO](https://min.io) (or [Garage](https://garagehq.deuxfleurs.fr) for a
  lighter footprint) as an S3-compatible object store, with a small custom control-plane
  (`/enroll`, `/config`, `/presign` from §3 — on the order of a hundred lines) that checks device
  tokens and mints MinIO presigned URLs. Audio bytes flow phone → MinIO directly; the
  control-plane never touches the heavy traffic. Put Cloudflare in front in reverse-proxy mode
  (free tier) for DDoS/WAF/rate-limiting regardless of the origin being self-hosted.
- **Alternative**: Nextcloud (WebDAV + app-password auth) if avoiding any custom storage-layer
  code matters more than having server-driven format/retention config — Nextcloud has no native
  concept of the latter, so either hardcode format/retention client-side (contradicts §3) or keep
  a small `/config`-only shim in front of it anyway.

The client-side enrollment/presign contract in §3 is the same regardless of which backend is
chosen.

## 13. Branding & identity

- App name: **LR**
- Package id: `ai.lequipe.lr`
- License: GPLv3 (`LICENSE` unchanged), with the attribution line in About (§8) and this fork's
  changes noted in `CHANGELOG.md`.

## 14. Open items for implementation time

- `WidgetRecordDisplayConfigureActivity` contents haven't been inspected in detail — confirm it
  has nothing theming-specific to strip before leaving it untouched.
- No unit/instrumentation tests exist in this repo today. The new sidecar-status parsing and
  retention-date math are pure logic and worth covering with basic unit tests as part of
  implementation, even though nothing else in the codebase currently has them.
- Exact WorkManager backoff constants (initial delay, cap, max attempts) are placeholders above
  and should be tuned during implementation.
