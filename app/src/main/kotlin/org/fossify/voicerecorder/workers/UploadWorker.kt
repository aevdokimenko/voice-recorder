package org.fossify.voicerecorder.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.EnrollmentClient
import org.fossify.voicerecorder.helpers.UploadResult
import org.fossify.voicerecorder.helpers.UploadState
import org.fossify.voicerecorder.helpers.UploadStatus
import org.fossify.voicerecorder.helpers.mimeTypeForRecording
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

        val config = applicationContext.config
        if (!config.isEnrolled) {
            return@withContext Result.failure()
        }

        val attempts = (readUploadStatus(path)?.attempts ?: 0) + 1
        writeUploadStatus(path, UploadStatus(state = UploadState.UPLOADING, attempts = attempts))
        notifyListChanged()

        val uploadUrl = EnrollmentClient.presign(
            host = config.serverHost,
            deviceToken = config.deviceToken,
            filename = file.name,
            contentType = mimeTypeForRecording(file.name),
            sizeBytes = file.length()
        )

        if (uploadUrl == null) {
            // Could be an expired token or a server blip; both are worth another attempt.
            return@withContext if (attempts >= MAX_ATTEMPTS) {
                markFailed(path, attempts, "presign failed")
                Result.failure()
            } else {
                Result.retry()
            }
        }

        when (val result = uploadRecording(file, uploadUrl)) {
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
                    // Leave the sidecar as UPLOADING; WorkManager runs us again after its
                    // backoff and the attempt counter carries over.
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
