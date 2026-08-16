package org.fossify.voicerecorder.extensions

import android.app.Activity
import android.view.WindowManager
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.helpers.UploadState
import org.fossify.voicerecorder.helpers.deleteUploadStatus
import org.fossify.voicerecorder.helpers.moveUploadStatus
import org.fossify.voicerecorder.helpers.isOlderThanDays
import org.fossify.voicerecorder.helpers.readUploadStatus
import org.fossify.voicerecorder.models.Recording
import java.io.File

fun Activity.setKeepScreenAwake(keepScreenOn: Boolean) {
    if (keepScreenOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

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

fun BaseSimpleActivity.trashRecordings(
    recordingsToMove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) = moveRecordings(
    recordingsToMove = recordingsToMove,
    destinationParent = getOrCreateTrashFolder(),
    callback = callback
)

fun BaseSimpleActivity.restoreRecordings(
    recordingsToRestore: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) = moveRecordings(
    recordingsToMove = recordingsToRestore,
    destinationParent = recordingsFolder,
    callback = callback
)

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

fun BaseSimpleActivity.deleteTrashedRecordings() {
    deleteRecordings(getAllRecordings(trashed = true)) {}
}

/**
 * Runs once a day. Uploaded recordings are trashed after the server's retention window, and
 * trashed recordings are purged after the longer one.
 */
fun BaseSimpleActivity.applyRetentionPolicy() {
    if (config.lastRecycleBinCheck >= System.currentTimeMillis() - DAY_SECONDS * 1000) {
        return
    }

    config.lastRecycleBinCheck = System.currentTimeMillis()
    ensureBackgroundThread {
        try {
            autoTrashUploadedRecordings()
            purgeExpiredTrashedRecordings()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

private fun BaseSimpleActivity.autoTrashUploadedRecordings() {
    val dueForTrash = getAllRecordings().filter { recording ->
        val status = readUploadStatus(recording.path)
        status?.state == UploadState.UPLOADED &&
            isOlderThanDays(status.uploadedAt, config.daysUntilTrash)
    }

    if (dueForTrash.isNotEmpty()) {
        trashRecordings(dueForTrash) {}
    }
}

private fun BaseSimpleActivity.purgeExpiredTrashedRecordings() {
    val expired = getAllRecordings(trashed = true)
        .filter { isOlderThanDays(it.timestamp, config.daysUntilPurge) }
    if (expired.isNotEmpty()) {
        deleteRecordings(expired) {}
    }
}
