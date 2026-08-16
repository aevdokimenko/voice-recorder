package org.fossify.voicerecorder.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.EnrollmentClient

/**
 * Picks up server-side changes to recording format and retention without needing the user to
 * scan a new QR code.
 */
class ConfigRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val UNIQUE_NAME = "config-refresh"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = applicationContext.config
        if (!config.isEnrolled) {
            return@withContext Result.success()
        }

        val fetched = EnrollmentClient.fetchConfig(config.serverHost, config.deviceToken)
            ?: return@withContext Result.retry()

        val (format, retention) = fetched
        config.applyServerFormat(format)
        config.applyServerRetention(retention)
        Result.success()
    }
}
