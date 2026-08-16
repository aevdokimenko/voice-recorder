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
