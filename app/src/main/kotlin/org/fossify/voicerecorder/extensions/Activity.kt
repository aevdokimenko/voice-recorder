package org.fossify.voicerecorder.extensions

import android.app.Activity
import android.view.WindowManager
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.MONTH_SECONDS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.helpers.deleteUploadStatus
import org.fossify.voicerecorder.helpers.moveUploadStatus
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

fun BaseSimpleActivity.deleteExpiredTrashedRecordings() {
    if (config.lastRecycleBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000) {
        config.lastRecycleBinCheck = System.currentTimeMillis()
        ensureBackgroundThread {
            try {
                val recordingsToRemove = getAllRecordings(trashed = true)
                    .filter { it.timestamp < System.currentTimeMillis() - MONTH_SECONDS * 1000L }
                if (recordingsToRemove.isNotEmpty()) {
                    deleteRecordings(recordingsToRemove) {}
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
