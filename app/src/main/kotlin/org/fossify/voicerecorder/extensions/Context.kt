package org.fossify.voicerecorder.extensions

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.fossify.commons.extensions.getDuration
import org.fossify.commons.extensions.isAudioFast
import org.fossify.voicerecorder.helpers.Config
import org.fossify.voicerecorder.helpers.IS_RECORDING
import org.fossify.voicerecorder.helpers.MyWidgetRecordDisplayProvider
import org.fossify.voicerecorder.helpers.RECORDINGS_FOLDER_NAME
import org.fossify.voicerecorder.helpers.TOGGLE_WIDGET_UI
import org.fossify.voicerecorder.helpers.generateRecordingFilename
import org.fossify.voicerecorder.helpers.readUploadStatus
import org.fossify.voicerecorder.models.Recording
import org.fossify.voicerecorder.workers.UploadWorker
import java.io.File
import java.util.concurrent.TimeUnit

val Context.config: Config get() = Config.newInstance(applicationContext)

/**
 * Recordings live in app-specific external storage, which needs no runtime permission and no SAF
 * on any supported API level. Recordings are staging for upload rather than a user-managed
 * library, so the folder is fixed and `Recording.path` is always a plain filesystem path.
 */
val Context.recordingsFolder: String
    get() {
        val folder = getExternalFilesDir(RECORDINGS_FOLDER_NAME)
            ?: File(filesDir, RECORDINGS_FOLDER_NAME)
        if (!folder.exists()) {
            folder.mkdirs()
        }

        return folder.absolutePath
    }

val Context.trashFolder: String
    get() = "$recordingsFolder/.trash"

fun Context.getOrCreateTrashFolder(): String {
    val folder = File(trashFolder)
    if (!folder.exists()) {
        folder.mkdirs()
    }

    return trashFolder
}

fun Context.drawableToBitmap(drawable: Drawable): Bitmap {
    val size = (60 * resources.displayMetrics.density).toInt()
    val mutableBitmap = createBitmap(size, size)
    val canvas = Canvas(mutableBitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return mutableBitmap
}

fun Context.updateWidgets(isRecording: Boolean) {
    val widgetIDs = AppWidgetManager.getInstance(applicationContext)
        ?.getAppWidgetIds(
            ComponentName(
                applicationContext,
                MyWidgetRecordDisplayProvider::class.java
            )
        ) ?: return

    if (widgetIDs.isNotEmpty()) {
        Intent(applicationContext, MyWidgetRecordDisplayProvider::class.java).apply {
            action = TOGGLE_WIDGET_UI
            putExtra(IS_RECORDING, isRecording)
            sendBroadcast(this)
        }
    }
}

fun Context.getAllRecordings(trashed: Boolean = false): ArrayList<Recording> {
    val folder = if (trashed) trashFolder else recordingsFolder
    val files = File(folder).listFiles() ?: return ArrayList()
    val recordings = ArrayList<Recording>()

    // isFile skips the nested .trash directory when listing the recordings folder.
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

    return recordings
}

private const val UPLOAD_BACKOFF_SECONDS = 30L

fun Context.enqueueUpload(recordingPath: String) {
    if (config.uploadEndpoint.isBlank()) {
        return
    }

    val input = Data.Builder()
        .putString(UploadWorker.KEY_RECORDING_PATH, recordingPath)
        .build()
    val request = OneTimeWorkRequestBuilder<UploadWorker>()
        .setInputData(input)
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        )
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

fun Context.getFormattedFilename(): String = generateRecordingFilename()
