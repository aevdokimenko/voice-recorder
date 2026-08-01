# LR — Upload Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically upload each finished recording to a configured HTTPS endpoint over TLS, with durable retry and a visible per-recording status, so a meeting recording reaches the server without the user doing anything.

**Architecture:** `RecorderService` enqueues a unique WorkManager job when a recording finishes. A `CoroutineWorker` PUTs the file to `{endpoint}/{filename}` with a bearer token and records the outcome in a small JSON *sidecar* file next to the recording. The recordings list reads those sidecars during its existing directory scan and renders a status badge, with tap-to-retry on failure. No database — sidecars keep the "no history store" decision from the design spec.

**Tech Stack:** Kotlin, WorkManager (`androidx.work:work-runtime`), `HttpURLConnection`, `org.json` (both platform APIs, no new networking or JSON dependency), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-07-29-lr-meeting-recorder-design.md`

## Global Constraints

- **Threat model for this phase: the server is a trusted box the user operates.** Transport security is TLS only — there is deliberately **no client-side/end-to-end encryption**. Do not add one in this plan.
- Recordings stay in **app-specific external storage** (`getExternalFilesDir("Recordings")`). Do not move them to shared/public storage: it would make confidential audio readable by any app holding `READ_MEDIA_AUDIO`.
- **Out of scope, deferred to Phase 2:** QR enrollment, the `/enroll` + `/config` + `/presign` contract, server-driven format and retention, and auto-trash-after-upload. Phase 1 configures the endpoint from `BuildConfig` defaults.
- Package/namespace stays `org.fossify.voicerecorder`; applicationId is `ai.lequipe.lr`.
- Detekt must stay clean: `maxIssues = 0`, max line length **120**, `MagicNumber` allows only `-1, 0, 1, 2, 42, 1000` — every timeout, buffer size, and HTTP status code needs a named constant.
- Do not edit `app/detekt-baseline.xml`, `app/lint-baseline.xml`, or anything under `commons/` (vendored upstream; see `commons/LOCAL_PATCHES.md`).
- Verify with `./gradlew assembleDebug detekt testDebugUnitTest lint`. `JAVA_HOME=/opt/homebrew/opt/openjdk@17`.
- Commits: Conventional Commits.

## File Structure

| File | Responsibility |
|---|---|
| `helpers/UploadStatus.kt` (new) | The status enum + sidecar read/write/move/delete. Pure file+JSON logic, unit tested. |
| `helpers/Uploader.kt` (new) | One function: PUT a file over HTTPS, return a typed result. No Android dependencies beyond `Log`. |
| `workers/UploadWorker.kt` (new) | WorkManager glue: read config, call `Uploader`, write the sidecar, decide retry. |
| `models/Recording.kt` | Gains an `uploadStatus` field. |
| `extensions/Context.kt` | `getAllRecordings()` attaches sidecar status; `enqueueUpload`/`cancelUpload` helpers. |
| `extensions/Activity.kt` | Trash/restore/delete carry the sidecar alongside the recording. |
| `adapters/RecordingsAdapter.kt` | Renders the badge; tap retries a failed upload. |
| `services/RecorderService.kt` | Enqueues the upload when a recording finishes. |

---

### Task 1: Add networking dependencies and endpoint configuration

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `gradle.properties`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt`

**Interfaces:**
- Produces: `Config.uploadEndpoint: String` and `Config.uploadToken: String`, both defaulting to the `BuildConfig` values. Consumed by Task 4.

- [ ] **Step 1: Add the WorkManager dependency to the catalog**

In `gradle/libs.versions.toml`, add to `[versions]` (next to the other `#Androidx` entries):

```toml
androidx-work = "2.10.1"
```

and to `[libraries]`:

```toml
androidx-work-runtime = { module = "androidx.work:work-runtime", version.ref = "androidx-work" }
```

Note this is `work-runtime`, **not** `work-runtime-ktx`. Since WorkManager 2.10 the `-ktx` artifact is empty; `CoroutineWorker` and the request builders live in the main artifact.

- [ ] **Step 2: Wire the dependency and BuildConfig fields into the app module**

In `app/build.gradle.kts`, add to `dependencies`:

```kotlin
    implementation(libs.androidx.work.runtime)
```

and inside `android { defaultConfig { ... } }`, after `vectorDrawables.useSupportLibrary = true`:

```kotlin
        buildConfigField(
            "String",
            "UPLOAD_ENDPOINT",
            "\"${project.findProperty("UPLOAD_ENDPOINT") ?: ""}\""
        )
        buildConfigField(
            "String",
            "UPLOAD_TOKEN",
            "\"${project.findProperty("UPLOAD_TOKEN") ?: ""}\""
        )
```

- [ ] **Step 3: Add the properties with empty defaults**

Append to `gradle.properties`:

```properties

# Upload endpoint (Phase 1). Override locally, e.g. in ~/.gradle/gradle.properties, with the
# base URL of your own server; recordings are PUT to <endpoint>/<filename>. Leave blank to
# disable uploading. Phase 2 replaces this with QR enrollment.
UPLOAD_ENDPOINT=
UPLOAD_TOKEN=
```

Keep both blank here — a real endpoint or token must never be committed. Override them in `~/.gradle/gradle.properties` or with `-PUPLOAD_ENDPOINT=...` on the command line.

- [ ] **Step 4: Add the INTERNET permission**

In `app/src/main/AndroidManifest.xml`, add alongside the other `uses-permission` entries:

```xml
    <uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 5: Add the preference keys and Config properties**

In `helpers/Constants.kt`, add to the shared-preferences block:

```kotlin
const val UPLOAD_ENDPOINT = "upload_endpoint"
const val UPLOAD_TOKEN = "upload_token"
```

In `helpers/Config.kt`, add (and add `import org.fossify.voicerecorder.BuildConfig`):

```kotlin
    var uploadEndpoint: String
        get() = prefs.getString(UPLOAD_ENDPOINT, BuildConfig.UPLOAD_ENDPOINT)!!
        set(uploadEndpoint) = prefs.edit { putString(UPLOAD_ENDPOINT, uploadEndpoint) }

    var uploadToken: String
        get() = prefs.getString(UPLOAD_TOKEN, BuildConfig.UPLOAD_TOKEN)!!
        set(uploadToken) = prefs.edit { putString(UPLOAD_TOKEN, uploadToken) }
```

- [ ] **Step 6: Verify it builds and the permission landed**

Run: `./gradlew assembleDebug detekt`
Expected: both succeed.

Run: `unzip -p app/build/outputs/apk/debug/lr-1-debug.apk AndroidManifest.xml | strings | grep -c "android.permission.INTERNET"`
Expected: `1` or more.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts gradle.properties \
  app/src/main/AndroidManifest.xml \
  app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt
git commit -m "feat: add WorkManager, INTERNET permission and upload endpoint config"
```

---

### Task 2: Sidecar upload-status store

**Files:**
- Create: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/UploadStatus.kt`
- Create: `app/src/test/kotlin/org/fossify/voicerecorder/helpers/UploadStatusTest.kt`

**Interfaces:**
- Produces, all consumed by Tasks 3-6:
  - `enum class UploadState { PENDING, UPLOADING, UPLOADED, FAILED }`
  - `data class UploadStatus(val state: UploadState, val attempts: Int = 0, val lastError: String? = null, val uploadedAt: Long = 0L)`
  - `fun sidecarFileFor(recordingPath: String): File`
  - `fun readUploadStatus(recordingPath: String): UploadStatus?` — null when no sidecar exists
  - `fun writeUploadStatus(recordingPath: String, status: UploadStatus)`
  - `fun deleteUploadStatus(recordingPath: String)`
  - `fun moveUploadStatus(fromRecordingPath: String, toRecordingPath: String)`

Sidecars are named `<recording file name>.status.json`, e.g. `20260801_140000.m4a.status.json`. They need no filtering in `getAllRecordings()` because commons' `File.isAudioFast()` matches only audio extensions.

`org.json` is used rather than kotlinx-serialization: it is part of the platform, the schema is four fields, and the module has no serialization plugin applied.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/org/fossify/voicerecorder/helpers/UploadStatusTest.kt`:

```kotlin
package org.fossify.voicerecorder.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UploadStatusTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun recording(name: String = "20260801_140000.m4a"): String {
        val file = File(tempFolder.root, name)
        file.writeText("audio")
        return file.absolutePath
    }

    @Test
    fun `sidecar sits next to the recording with a status json suffix`() {
        val path = recording()
        assertEquals("20260801_140000.m4a.status.json", sidecarFileFor(path).name)
        assertEquals(tempFolder.root.absolutePath, sidecarFileFor(path).parentFile!!.absolutePath)
    }

    @Test
    fun `reading a recording with no sidecar returns null`() {
        assertNull(readUploadStatus(recording()))
    }

    @Test
    fun `round-trips every field`() {
        val path = recording()
        val written = UploadStatus(
            state = UploadState.FAILED,
            attempts = 3,
            lastError = "HTTP 503",
            uploadedAt = 1_770_000_000_000L
        )
        writeUploadStatus(path, written)

        assertEquals(written, readUploadStatus(path))
    }

    @Test
    fun `round-trips a null lastError`() {
        val path = recording()
        writeUploadStatus(path, UploadStatus(state = UploadState.UPLOADED, uploadedAt = 42L))

        val read = readUploadStatus(path)!!
        assertEquals(UploadState.UPLOADED, read.state)
        assertNull(read.lastError)
        assertEquals(42L, read.uploadedAt)
    }

    @Test
    fun `a corrupt sidecar reads as null rather than throwing`() {
        val path = recording()
        sidecarFileFor(path).writeText("{ not json")

        assertNull(readUploadStatus(path))
    }

    @Test
    fun `an unknown state reads as null rather than throwing`() {
        val path = recording()
        sidecarFileFor(path).writeText("""{"state":"TELEPORTED","attempts":0}""")

        assertNull(readUploadStatus(path))
    }

    @Test
    fun `delete removes the sidecar`() {
        val path = recording()
        writeUploadStatus(path, UploadStatus(UploadState.PENDING))
        assertTrue(sidecarFileFor(path).exists())

        deleteUploadStatus(path)

        assertFalse(sidecarFileFor(path).exists())
    }

    @Test
    fun `move relocates the sidecar to the new recording path`() {
        val from = recording()
        val to = File(tempFolder.newFolder("trash"), "20260801_140000.m4a").absolutePath
        writeUploadStatus(from, UploadStatus(UploadState.UPLOADED, attempts = 1))

        moveUploadStatus(from, to)

        assertFalse(sidecarFileFor(from).exists())
        assertEquals(UploadState.UPLOADED, readUploadStatus(to)!!.state)
    }

    @Test
    fun `moving a recording that has no sidecar is a no-op`() {
        val from = recording()
        val to = File(tempFolder.newFolder("trash"), "20260801_140000.m4a").absolutePath

        moveUploadStatus(from, to)

        assertNull(readUploadStatus(to))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "org.fossify.voicerecorder.helpers.UploadStatusTest"`
Expected: FAIL — unresolved references (`sidecarFileFor`, `UploadStatus`, etc.).

- [ ] **Step 3: Implement the sidecar store**

Create `app/src/main/kotlin/org/fossify/voicerecorder/helpers/UploadStatus.kt`:

```kotlin
package org.fossify.voicerecorder.helpers

import org.json.JSONObject
import java.io.File

private const val SIDECAR_SUFFIX = ".status.json"
private const val KEY_STATE = "state"
private const val KEY_ATTEMPTS = "attempts"
private const val KEY_LAST_ERROR = "lastError"
private const val KEY_UPLOADED_AT = "uploadedAt"

enum class UploadState { PENDING, UPLOADING, UPLOADED, FAILED }

data class UploadStatus(
    val state: UploadState,
    val attempts: Int = 0,
    val lastError: String? = null,
    val uploadedAt: Long = 0L
)

fun sidecarFileFor(recordingPath: String): File = File(recordingPath + SIDECAR_SUFFIX)

/**
 * Returns null when there is no sidecar, or when one exists but cannot be understood — a
 * recording whose status is unreadable is treated the same as one that was never uploaded,
 * which is the safe direction (it gets retried rather than silently considered done).
 */
fun readUploadStatus(recordingPath: String): UploadStatus? {
    val file = sidecarFileFor(recordingPath)
    if (!file.exists()) {
        return null
    }

    return try {
        val json = JSONObject(file.readText())
        UploadStatus(
            state = UploadState.valueOf(json.getString(KEY_STATE)),
            attempts = json.optInt(KEY_ATTEMPTS, 0),
            lastError = if (json.isNull(KEY_LAST_ERROR)) null else json.optString(KEY_LAST_ERROR),
            uploadedAt = json.optLong(KEY_UPLOADED_AT, 0L)
        )
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
        null
    }
}

fun writeUploadStatus(recordingPath: String, status: UploadStatus) {
    val json = JSONObject()
        .put(KEY_STATE, status.state.name)
        .put(KEY_ATTEMPTS, status.attempts)
        .put(KEY_LAST_ERROR, status.lastError ?: JSONObject.NULL)
        .put(KEY_UPLOADED_AT, status.uploadedAt)
    sidecarFileFor(recordingPath).writeText(json.toString())
}

fun deleteUploadStatus(recordingPath: String) {
    sidecarFileFor(recordingPath).delete()
}

fun moveUploadStatus(fromRecordingPath: String, toRecordingPath: String) {
    val source = sidecarFileFor(fromRecordingPath)
    if (source.exists()) {
        source.renameTo(sidecarFileFor(toRecordingPath))
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "org.fossify.voicerecorder.helpers.UploadStatusTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/fossify/voicerecorder/helpers/UploadStatus.kt \
  app/src/test/kotlin/org/fossify/voicerecorder/helpers/UploadStatusTest.kt
git commit -m "feat: add sidecar-file upload status store"
```

---

### Task 3: The HTTPS uploader

**Files:**
- Create: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Uploader.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces, consumed by Task 4:
  - `sealed interface UploadResult { object Success; data class Retryable(val reason: String); data class Permanent(val reason: String) }`
  - `fun uploadRecording(file: File, endpoint: String, token: String): UploadResult`

The split between `Retryable` and `Permanent` is what stops the worker retrying forever against a 401 or a 404: connection failures, timeouts and 5xx are worth retrying; 4xx other than 408/429 are not.

- [ ] **Step 1: Implement the uploader**

Create `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Uploader.kt`:

```kotlin
package org.fossify.voicerecorder.helpers

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 60_000
private const val UPLOAD_BUFFER_BYTES = 8 * 1024
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FLOOR = 500

sealed interface UploadResult {
    data object Success : UploadResult

    /** Transient — worth another attempt (offline, timeout, 5xx, 408, 429). */
    data class Retryable(val reason: String) : UploadResult

    /** The server rejected this in a way retrying will not fix (auth, bad request). */
    data class Permanent(val reason: String) : UploadResult
}

/**
 * PUTs [file] to `<endpoint>/<file name>` with a bearer token. Streams the body so a long
 * recording is never held in memory. Transport security is plain TLS: this phase assumes the
 * endpoint is a server the user operates and trusts.
 */
fun uploadRecording(file: File, endpoint: String, token: String): UploadResult {
    val target = "${endpoint.trimEnd('/')}/${file.name}"
    var connection: HttpURLConnection? = null

    return try {
        connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", mimeTypeForRecording(file.name))
        }

        file.inputStream().use { input ->
            connection.outputStream.use { output ->
                input.copyTo(output, UPLOAD_BUFFER_BYTES)
            }
        }

        classifyResponse(connection.responseCode)
    } catch (e: IOException) {
        UploadResult.Retryable(e.message ?: e::class.java.simpleName)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        UploadResult.Permanent(e.message ?: e::class.java.simpleName)
    } finally {
        connection?.disconnect()
    }
}

private fun classifyResponse(code: Int): UploadResult = when {
    code in HttpURLConnection.HTTP_OK..HttpURLConnection.HTTP_PARTIAL -> UploadResult.Success
    code >= HTTP_SERVER_ERROR_FLOOR -> UploadResult.Retryable("HTTP $code")
    code == HttpURLConnection.HTTP_CLIENT_TIMEOUT -> UploadResult.Retryable("HTTP $code")
    code == HTTP_TOO_MANY_REQUESTS -> UploadResult.Retryable("HTTP $code")
    else -> UploadResult.Permanent("HTTP $code")
}

private fun mimeTypeForRecording(fileName: String): String = when {
    fileName.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
    else -> "audio/mp4"
}
```

- [ ] **Step 2: Verify it compiles cleanly**

Run: `./gradlew assembleDebug detekt`
Expected: both succeed.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/fossify/voicerecorder/helpers/Uploader.kt
git commit -m "feat: add streaming HTTPS uploader with retryable/permanent outcomes"
```

---

### Task 4: The upload worker, enqueued when a recording finishes

**Files:**
- Create: `app/src/main/kotlin/org/fossify/voicerecorder/workers/UploadWorker.kt`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/extensions/Context.kt`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/services/RecorderService.kt`

**Interfaces:**
- Consumes: `UploadStatus`/`UploadState`/`writeUploadStatus`/`readUploadStatus` (Task 2), `uploadRecording`/`UploadResult` (Task 3), `Config.uploadEndpoint`/`uploadToken` (Task 1).
- Produces, consumed by Tasks 5-6:
  - `fun Context.enqueueUpload(recordingPath: String)`
  - `fun Context.cancelUpload(recordingPath: String)`
  - `UploadWorker.uniqueWorkNameFor(recordingPath: String): String`

- [ ] **Step 1: Implement the worker**

Create `app/src/main/kotlin/org/fossify/voicerecorder/workers/UploadWorker.kt`:

```kotlin
package org.fossify.voicerecorder.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.UploadResult
import org.fossify.voicerecorder.helpers.UploadState
import org.fossify.voicerecorder.helpers.UploadStatus
import org.fossify.voicerecorder.helpers.readUploadStatus
import org.fossify.voicerecorder.helpers.uploadRecording
import org.fossify.voicerecorder.helpers.writeUploadStatus
import org.fossify.voicerecorder.models.Events
import org.greenrobot.eventbus.EventBus
import java.io.File

class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_RECORDING_PATH = "recording_path"
        const val MAX_ATTEMPTS = 5

        fun uniqueWorkNameFor(recordingPath: String) = "upload:$recordingPath"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString(KEY_RECORDING_PATH) ?: return@withContext Result.failure()
        val file = File(path)

        // The recording was deleted or trashed while this job was queued.
        if (!file.exists()) {
            return@withContext Result.failure()
        }

        val endpoint = applicationContext.config.uploadEndpoint
        val token = applicationContext.config.uploadToken
        if (endpoint.isBlank()) {
            return@withContext Result.failure()
        }

        val attempts = (readUploadStatus(path)?.attempts ?: 0) + 1
        writeUploadStatus(path, UploadStatus(state = UploadState.UPLOADING, attempts = attempts))
        notifyListChanged()

        when (val result = uploadRecording(file, endpoint, token)) {
            is UploadResult.Success -> {
                writeUploadStatus(
                    recordingPath = path,
                    status = UploadStatus(
                        state = UploadState.UPLOADED,
                        attempts = attempts,
                        uploadedAt = System.currentTimeMillis()
                    )
                )
                notifyListChanged()
                Result.success()
            }

            is UploadResult.Retryable -> {
                if (attempts >= MAX_ATTEMPTS) {
                    markFailed(path, attempts, result.reason)
                    Result.failure()
                } else {
                    // Leave the sidecar as UPLOADING; WorkManager will run us again after
                    // its backoff and the attempt counter carries over.
                    Result.retry()
                }
            }

            is UploadResult.Permanent -> {
                markFailed(path, attempts, result.reason)
                Result.failure()
            }
        }
    }

    private fun markFailed(path: String, attempts: Int, reason: String) {
        writeUploadStatus(
            recordingPath = path,
            status = UploadStatus(
                state = UploadState.FAILED,
                attempts = attempts,
                lastError = reason
            )
        )
        notifyListChanged()
    }

    private fun notifyListChanged() {
        EventBus.getDefault().post(Events.RecordingUploadUpdated())
    }
}
```

- [ ] **Step 2: Add the event the worker posts**

In `app/src/main/kotlin/org/fossify/voicerecorder/models/Events.kt`, add inside `class Events`:

```kotlin
    class RecordingUploadUpdated internal constructor()
```

- [ ] **Step 3: Add the enqueue/cancel helpers**

In `app/src/main/kotlin/org/fossify/voicerecorder/extensions/Context.kt`, add these imports:

```kotlin
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.fossify.voicerecorder.workers.UploadWorker
import java.util.concurrent.TimeUnit
```

and these functions:

```kotlin
private const val UPLOAD_BACKOFF_SECONDS = 30L

fun Context.enqueueUpload(recordingPath: String) {
    if (config.uploadEndpoint.isBlank()) {
        return
    }

    val request = OneTimeWorkRequestBuilder<UploadWorker>()
        .setInputData(Data.Builder().putString(UploadWorker.KEY_RECORDING_PATH, recordingPath).build())
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, UPLOAD_BACKOFF_SECONDS, TimeUnit.SECONDS)
        .build()

    WorkManager.getInstance(applicationContext).enqueueUniqueWork(
        UploadWorker.uniqueWorkNameFor(recordingPath),
        ExistingWorkPolicy.REPLACE,
        request
    )
}

fun Context.cancelUpload(recordingPath: String) {
    WorkManager.getInstance(applicationContext)
        .cancelUniqueWork(UploadWorker.uniqueWorkNameFor(recordingPath))
}
```

`UPLOAD_BACKOFF_SECONDS` is the initial delay; `BackoffPolicy.EXPONENTIAL` doubles it per attempt, so five attempts span roughly 30s, 1m, 2m, 4m.

- [ ] **Step 4: Enqueue when a recording finishes**

In `services/RecorderService.kt`, add `import org.fossify.voicerecorder.extensions.enqueueUpload`, and change the block inside `stopRecording()`:

```kotlin
            ensureBackgroundThread {
                toast(R.string.recording_saved_successfully)
                enqueueUpload(recordingPath)
                EventBus.getDefault().post(Events.RecordingCompleted())
            }
```

Enqueueing from a background thread while the service is in the foreground is fine — WorkManager has no background-start restriction, unlike starting a foreground service.

- [ ] **Step 5: Verify it builds**

Run: `./gradlew assembleDebug detekt testDebugUnitTest`
Expected: all succeed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/org/fossify/voicerecorder/workers/UploadWorker.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/models/Events.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/extensions/Context.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/services/RecorderService.kt
git commit -m "feat: upload recordings in the background with WorkManager retry"
```

---

### Task 5: Show upload status in the recordings list

**Files:**
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/models/Recording.kt`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/extensions/Context.kt`
- Modify: `app/src/main/res/layout/item_recording.xml`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/adapters/RecordingsAdapter.kt`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/fragments/RecordingsFragment.kt`

**Interfaces:**
- Consumes: `readUploadStatus`, `UploadState` (Task 2); `enqueueUpload` (Task 4).
- Produces: `Recording.uploadState: UploadState?` — null means "never queued".

- [ ] **Step 1: Add the field to the model**

```kotlin
package org.fossify.voicerecorder.models

import org.fossify.voicerecorder.helpers.UploadState

data class Recording(
    val id: Int,
    val title: String,
    val path: String,
    val timestamp: Long,
    val duration: Int,
    val size: Int,
    val uploadState: UploadState? = null
)
```

- [ ] **Step 2: Attach the sidecar status during the directory scan**

In `extensions/Context.kt`, inside `getAllRecordings()`, add `uploadState` to the constructed `Recording` (and `import org.fossify.voicerecorder.helpers.readUploadStatus`):

```kotlin
    files.filter { it.isFile && it.isAudioFast() }.forEach {
        recordings.add(
            Recording(
                id = it.hashCode(),
                title = it.name,
                path = it.absolutePath,
                timestamp = it.lastModified(),
                duration = getDuration(it.absolutePath) ?: 0,
                size = it.length().toInt(),
                uploadState = readUploadStatus(it.absolutePath)?.state
            )
        )
    }
```

- [ ] **Step 3: Add the badge to the row layout**

In `app/src/main/res/layout/item_recording.xml`, add inside the `ConstraintLayout`, and re-point `recording_date`'s end constraint at it:

```xml
        <TextView
            android:id="@+id/recording_upload_status"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:ellipsize="end"
            android:maxLines="1"
            android:paddingStart="@dimen/small_margin"
            android:paddingEnd="@dimen/small_margin"
            android:textSize="@dimen/smaller_text_size"
            android:visibility="gone"
            app:layout_constraintBottom_toBottomOf="@+id/recording_date"
            app:layout_constraintEnd_toStartOf="@+id/recording_size"
            app:layout_constraintTop_toTopOf="@+id/recording_date"
            tools:text="Uploaded"
            tools:visibility="visible" />
```

Change `recording_date`'s `app:layout_constraintEnd_toStartOf` from `@+id/recording_size` to `@+id/recording_upload_status`.

- [ ] **Step 4: Add the status strings**

In `app/src/main/res/values/strings.xml`:

```xml
    <!-- Upload -->
    <string name="upload_pending">Waiting to upload</string>
    <string name="upload_in_progress">Uploading…</string>
    <string name="upload_done">Uploaded</string>
    <string name="upload_failed">Upload failed — tap to retry</string>
```

In `app/src/main/res/values-ru/strings.xml`:

```xml
    <string name="upload_pending">Ожидает отправки</string>
    <string name="upload_in_progress">Отправка…</string>
    <string name="upload_done">Отправлено</string>
    <string name="upload_failed">Ошибка отправки — нажмите, чтобы повторить</string>
```

- [ ] **Step 5: Render the badge and wire tap-to-retry**

In `adapters/RecordingsAdapter.kt`, add imports:

```kotlin
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.voicerecorder.extensions.enqueueUpload
import org.fossify.voicerecorder.helpers.UploadState
```

and inside `setupView(...)`'s `ItemRecordingBinding.bind(view).apply { ... }`, after the existing `recordingSize.text = ...` line:

```kotlin
            val uploadLabel = when (recording.uploadState) {
                UploadState.PENDING -> R.string.upload_pending
                UploadState.UPLOADING -> R.string.upload_in_progress
                UploadState.UPLOADED -> R.string.upload_done
                UploadState.FAILED -> R.string.upload_failed
                null -> null
            }

            recordingUploadStatus.beVisibleIf(uploadLabel != null)
            if (uploadLabel != null) {
                recordingUploadStatus.text = root.context.getString(uploadLabel)
                recordingUploadStatus.setTextColor(textColor)
            }

            recordingUploadStatus.setOnClickListener {
                if (recording.uploadState == UploadState.FAILED) {
                    activity.enqueueUpload(recording.path)
                    refreshListener.refreshRecordings()
                }
            }
```

- [ ] **Step 6: Refresh the list when an upload changes state**

In `fragments/RecordingsFragment.kt`, add a subscriber next to the existing ones:

```kotlin
    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingUploadUpdated(@Suppress("UNUSED_PARAMETER") event: Events.RecordingUploadUpdated) {
        refreshRecordings()
    }
```

- [ ] **Step 7: Verify**

Run: `./gradlew assembleDebug detekt testDebugUnitTest lint`
Expected: all succeed.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/org/fossify/voicerecorder/models/Recording.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/extensions/Context.kt \
  app/src/main/res/layout/item_recording.xml \
  app/src/main/res/values/strings.xml app/src/main/res/values-ru/strings.xml \
  app/src/main/kotlin/org/fossify/voicerecorder/adapters/RecordingsAdapter.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/fragments/RecordingsFragment.kt
git commit -m "feat: show per-recording upload status with tap-to-retry"
```

---

### Task 6: Keep sidecars in step with trash, restore and delete

**Files:**
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/extensions/Activity.kt`

**Interfaces:**
- Consumes: `moveUploadStatus`, `deleteUploadStatus` (Task 2); `cancelUpload` (Task 4).

Without this, trashing a recording orphans its sidecar in the recordings folder, and a queued upload keeps running against a file that has moved.

- [ ] **Step 1: Carry the sidecar through moves and deletes**

In `extensions/Activity.kt`, add imports:

```kotlin
import org.fossify.voicerecorder.helpers.deleteUploadStatus
import org.fossify.voicerecorder.helpers.moveUploadStatus
```

Replace `deleteRecordings` and `moveRecordings` with:

```kotlin
fun BaseSimpleActivity.deleteRecordings(
    recordingsToRemove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) {
    ensureBackgroundThread {
        recordingsToRemove.forEach {
            cancelUpload(it.path)
            deleteUploadStatus(it.path)
            File(it.path).delete()
        }

        callback(true)
    }
}

/**
 * The recordings folder and its .trash subfolder are on the same volume, so a rename moves the
 * file without copying. The upload sidecar moves with it, and any queued upload is cancelled
 * first so it cannot race the move.
 */
fun BaseSimpleActivity.moveRecordings(
    recordingsToMove: Collection<Recording>,
    destinationParent: String,
    callback: (success: Boolean) -> Unit
) {
    ensureBackgroundThread {
        File(destinationParent).mkdirs()
        recordingsToMove.forEach { recording ->
            cancelUpload(recording.path)
            val target = File(destinationParent, recording.title)
            if (File(recording.path).renameTo(target)) {
                moveUploadStatus(recording.path, target.absolutePath)
            }
        }

        callback(true)
    }
}
```

`cancelUpload` needs no import here: it is declared in `extensions/Context.kt`, which is the same `org.fossify.voicerecorder.extensions` package as this file.

- [ ] **Step 2: Verify**

Run: `./gradlew assembleDebug detekt testDebugUnitTest`
Expected: all succeed.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/fossify/voicerecorder/extensions/Activity.kt
git commit -m "fix: move and delete upload sidecars alongside their recordings"
```

---

### Task 7: End-to-end verification against a real endpoint

**Files:** none (verification only).

- [ ] **Step 1: Run a throwaway receiver on the dev machine**

Any server that accepts `PUT /<filename>` works. A minimal one:

```bash
mkdir -p /tmp/lr-uploads && cd /tmp/lr-uploads
python3 - <<'PY'
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def do_PUT(self):
        n = int(self.headers.get('Content-Length', 0))
        name = self.path.lstrip('/') or 'unnamed'
        with open(name, 'wb') as f:
            f.write(self.rfile.read(n))
        print("received", name, n, "bytes, auth:", self.headers.get('Authorization'))
        self.send_response(200); self.end_headers()
HTTPServer(('0.0.0.0', 8000), H).serve_forever()
PY
```

Note this is **plain HTTP for local testing only** — the real endpoint must be HTTPS. Android blocks cleartext by default, so for this test either point at an HTTPS endpoint, or temporarily allow cleartext to `10.0.2.2` with a debug-only `network_security_config.xml`. Do not ship a config that permits cleartext generally.

- [ ] **Step 2: Build pointing at it**

From the emulator, the host is `10.0.2.2`:

```bash
./gradlew installDebug -PUPLOAD_ENDPOINT=http://10.0.2.2:8000 -PUPLOAD_TOKEN=test-token
```

- [ ] **Step 3: Walk the golden path**

1. Record something and save it. The row should show "Waiting to upload"/"Uploading…", then "Uploaded" within a few seconds, and the file should appear in `/tmp/lr-uploads/` with the same name and byte count. The receiver should log `auth: Bearer test-token`.
2. Stop the receiver, record again. The row should sit in "Uploading…" while WorkManager retries, and after five attempts flip to "Upload failed — tap to retry".
3. Restart the receiver and tap the failed row's badge. It should upload and flip to "Uploaded".
4. Turn on airplane mode and record. Nothing should be attempted; turn it off and the upload should start on its own (the `NetworkType.CONNECTED` constraint).
5. Record, then immediately trash the recording. Verify no orphan `.status.json` remains in the recordings folder:
   `adb shell run-as ai.lequipe.lr.debug ls /data/../..` — simpler: `adb shell ls /sdcard/Android/data/ai.lequipe.lr.debug/files/Recordings/`
6. Restore it from the recycle bin and confirm its badge still reads correctly.
7. Kill the app mid-upload (`adb shell am force-stop ai.lequipe.lr.debug`) and confirm the upload still completes — that is the whole reason for WorkManager over a bare thread.

- [ ] **Step 4: Confirm the scope boundary**

No commit. Confirm that QR enrollment, `/enroll`/`/config`/`/presign`, server-driven format and retention, and auto-trash-after-upload are **not** present — they are Phase 2. The endpoint in Phase 1 comes only from `BuildConfig`/`Config`, with no UI to change it.
