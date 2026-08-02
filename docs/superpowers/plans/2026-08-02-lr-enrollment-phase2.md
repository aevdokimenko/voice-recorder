# LR — Enrollment & Retention Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Phase 1's build-time endpoint with runtime enrollment — a well-known probe on first launch, falling back to a QR scan — and let the server drive recording format and retention, including auto-trashing uploaded recordings once they age out.

**Architecture:** An `EnrollmentClient` speaks three JSON endpoints (`/enroll`, `/config`, `/presign`) over `HttpURLConnection`. Enrollment results are cached in `Config`. `MainActivity` gates the UI on being enrolled, routing to a QR screen when the well-known probe fails. `UploadWorker` switches from a direct PUT to presign-then-PUT. A daily `ConfigRefreshWorker` re-reads server config, and the existing daily retention pass gains an auto-trash rule for uploaded recordings.

**Tech Stack:** Kotlin, `zxing-android-embedded` (no Google Play Services), WorkManager, `HttpURLConnection`, `org.json`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-07-29-lr-meeting-recorder-design.md` §3, §7
**Builds on:** `docs/superpowers/plans/2026-08-01-lr-upload-phase1.md`

## Global Constraints

- Server is a **trusted box the user operates**. TLS only; still no client-side encryption.
- Recordings stay in app-specific external storage.
- **No manual endpoint entry in the UI** — enrollment is well-known probe or QR only, per spec §2.
- **No Google Play Services.** QR scanning must use ZXing.
- Detekt clean: `maxIssues = 0`, 120-char lines, `MagicNumber` allows only `-1, 0, 1, 2, 42, 1000`.
- Do not edit `commons/` (vendored; see `commons/LOCAL_PATCHES.md`) or the lint/detekt baselines.
- Verify with `./gradlew assembleDebug detekt testDebugUnitTest lint`.

## HTTP contract

All JSON, all bearer-authenticated except the unauthenticated well-known enroll.

```
POST {host}/api/v1/enroll          body {clientId, deviceName}   [+ Bearer <qrToken>]
  -> 200 {deviceToken, format:{codec,bitrate,sampleRate},
          retention:{daysUntilTrash,daysUntilPurge}}

GET  {host}/api/v1/config          Bearer <deviceToken>
  -> 200 {format:{...}, retention:{...}}

POST {host}/api/v1/presign         Bearer <deviceToken>
     body {filename, contentType, sizeBytes}
  -> 200 {uploadUrl, expiresAt}

PUT  {uploadUrl}                   raw audio bytes, no bearer
  -> 2xx
```

QR payload is a URL: `https://host/api/v1/enroll?token=<opaque>`. The host is derived by stripping `/api/v1/enroll` from it.

---

### Task 1: Enrollment models and JSON parsing

**Files:**
- Create: `helpers/Enrollment.kt`, `app/src/test/.../EnrollmentTest.kt`

**Produces:** `ServerFormat(codec, bitrate, sampleRate)`, `ServerRetention(daysUntilTrash, daysUntilPurge)`, `Enrollment(deviceToken, format, retention)`, `parseEnrollment(json): Enrollment?`, `parseConfig(json): Pair<ServerFormat, ServerRetention>?`, `parsePresign(json): String?`, `enrollHostFrom(qrPayload): String?`.

Parsing returns null rather than throwing on anything malformed; callers treat null as "enrollment failed".

- [ ] **Step 1:** Write tests covering: a full valid enroll response; missing `deviceToken` → null; unknown codec → null; a `/config` response without `deviceToken`; presign extraction; host derivation from a QR URL with and without the `/api/v1/enroll` suffix; a non-URL QR payload → null.
- [ ] **Step 2:** Run, confirm they fail.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run, confirm pass.
- [ ] **Step 5:** Commit `feat: add enrollment payload parsing`.

---

### Task 2: Config storage for enrollment state

**Files:** `helpers/Constants.kt`, `helpers/Config.kt`

**Produces:** `Config.clientId` (generated UUID, stable), `deviceToken`, `serverHost`, `isEnrolled`, `daysUntilTrash`, `daysUntilPurge`, and `applyEnrollment(Enrollment)`.

Phase 1's `uploadEndpoint`/`uploadToken` are replaced by `serverHost`/`deviceToken`; `BuildConfig.UPLOAD_ENDPOINT` becomes the **well-known probe host** rather than a direct upload target. Retention defaults: 15 and 30 days.

- [ ] **Step 1:** Add keys and properties; `clientId` lazily generates and persists a UUID.
- [ ] **Step 2:** Remove `uploadEndpoint`/`uploadToken`, rename the BuildConfig field to `WELL_KNOWN_HOST`, update `gradle.properties`.
- [ ] **Step 3:** Build; commit `feat: store enrollment state in Config`.

---

### Task 3: Enrollment HTTP client

**Files:** `helpers/EnrollmentClient.kt`

**Produces:** `fun enroll(host, clientId, deviceName, qrToken: String?): Enrollment?`, `fun fetchConfig(host, deviceToken): Pair<ServerFormat, ServerRetention>?`, `fun presign(host, deviceToken, filename, contentType, sizeBytes): String?`. All return null on any failure. The well-known probe uses a short timeout (3s connect) so first launch is not held up.

- [ ] **Step 1:** Implement with named timeout constants.
- [ ] **Step 2:** Build + detekt; commit `feat: add enrollment HTTP client`.

---

### Task 4: QR scan screen

**Files:** `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`, `activities/EnrollmentActivity.kt`, `res/layout/activity_enrollment.xml`, strings (en + ru)

**Produces:** an activity that probes the well-known host, and on failure shows "Scan QR to connect" with a button launching ZXing's `ScanContract`; on a successful scan it enrolls and finishes with `RESULT_OK`.

- [ ] **Step 1:** Add `com.journeyapps:zxing-android-embedded` and the `CAMERA` permission.
- [ ] **Step 2:** Build the screen and wire `ScanContract`.
- [ ] **Step 3:** Build; commit `feat: add QR enrollment screen`.

---

### Task 5: Gate the app on enrollment

**Files:** `activities/MainActivity.kt`

`MainActivity` launches `EnrollmentActivity` when `!config.isEnrolled`, and finishes if enrollment is declined. Already-enrolled launches are unaffected.

- [ ] **Step 1:** Implement via `registerForActivityResult`.
- [ ] **Step 2:** Build; commit `feat: require enrollment before recording`.

---

### Task 6: Presign-based upload

**Files:** `helpers/Uploader.kt`, `workers/UploadWorker.kt`, `extensions/Context.kt`

`UploadWorker` calls `presign` then PUTs to the returned URL. `uploadRecording` takes an absolute `uploadUrl` instead of endpoint+token and drops the `Authorization` header. A failed presign is retryable.

- [ ] **Step 1:** Change signatures and the worker flow; keep the retryable/permanent classification.
- [ ] **Step 2:** Build + tests; commit `feat: upload via server-issued presigned URLs`.

---

### Task 7: Server-driven recording format

**Files:** `helpers/Config.kt`, `helpers/Enrollment.kt`

Map `codec` (`aac`/`opus`) to the existing `EXTENSION_M4A`/`EXTENSION_OGG`, and apply bitrate/sample rate. `applyEnrollment` writes these so `RecorderService` picks them up with no change.

- [ ] **Step 1:** Implement mapping + a unit test for codec→extension.
- [ ] **Step 2:** Build + tests; commit `feat: apply server-provided recording format`.

---

### Task 8: Daily config refresh and retention

**Files:** `workers/ConfigRefreshWorker.kt`, `extensions/Context.kt`, `extensions/Activity.kt`, `activities/MainActivity.kt`

- A `PeriodicWorkRequest` (1 day, `NetworkType.CONNECTED`) refreshes format/retention.
- `deleteExpiredTrashedRecordings` uses `config.daysUntilPurge` instead of the hardcoded month.
- New `autoTrashUploadedRecordings`: any recording whose sidecar is `UPLOADED` with `uploadedAt` older than `daysUntilTrash` is trashed. Runs in the same daily pass from `MainActivity`.

- [ ] **Step 1:** Unit-test the "is this due for trashing" predicate as pure logic.
- [ ] **Step 2:** Implement worker + retention pass.
- [ ] **Step 3:** Build + tests; commit `feat: refresh server config daily and auto-trash uploaded recordings`.

---

### Task 9: Settings surface and end-to-end verification

**Files:** `activities/SettingsActivity.kt`, `res/layout/activity_settings.xml`, strings

Settings shows the connected host (read-only) and a "Re-scan QR" row that relaunches `EnrollmentActivity`.

- [ ] **Step 1:** Implement.
- [ ] **Step 2:** Write a mock server implementing all four endpoints, install against it, and verify: first-run well-known enrollment; QR fallback; recording uses the server's format; upload goes through presign; re-scan works; auto-trash fires with a forced-short retention.
- [ ] **Step 3:** Commit.
