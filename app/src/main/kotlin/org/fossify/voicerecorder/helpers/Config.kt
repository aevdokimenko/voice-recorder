package org.fossify.voicerecorder.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import androidx.core.content.edit
import org.fossify.commons.helpers.BaseConfig
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import java.util.UUID

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    var extension: Int
        get() = prefs.getInt(EXTENSION, EXTENSION_M4A)
        set(extension) = prefs.edit().putInt(EXTENSION, extension).apply()

    var microphoneMode: Int
        get() = prefs.getInt(MICROPHONE_MODE, MediaRecorder.AudioSource.DEFAULT)
        set(audioSource) = prefs.edit { putInt(MICROPHONE_MODE, audioSource) }

    var bitrate: Int
        get() = prefs.getInt(BITRATE, DEFAULT_BITRATE)
        set(bitrate) = prefs.edit().putInt(BITRATE, bitrate).apply()

    var samplingRate: Int
        get() = prefs.getInt(SAMPLING_RATE, DEFAULT_SAMPLING_RATE)
        set(samplingRate) = prefs.edit().putInt(SAMPLING_RATE, samplingRate).apply()

    fun getExtension() = context.getString(
        when (extension) {
            EXTENSION_OGG -> R.string.ogg
            else -> R.string.m4a
        }
    )

    @SuppressLint("InlinedApi")
    fun getOutputFormat() = when (extension) {
        EXTENSION_OGG -> MediaRecorder.OutputFormat.OGG
        else -> MediaRecorder.OutputFormat.MPEG_4
    }

    @SuppressLint("InlinedApi")
    fun getAudioEncoder() = when (extension) {
        EXTENSION_OGG -> MediaRecorder.AudioEncoder.OPUS
        else -> MediaRecorder.AudioEncoder.AAC
    }

    var lastRecycleBinCheck: Long
        get() = prefs.getLong(LAST_RECYCLE_BIN_CHECK, 0L)
        set(lastRecycleBinCheck) = prefs.edit().putLong(LAST_RECYCLE_BIN_CHECK, lastRecycleBinCheck)
            .apply()

    /** Stable per-install identifier sent with every enrollment. */
    val clientId: String
        get() {
            val existing = prefs.getString(CLIENT_ID, "")!!
            if (existing.isNotBlank()) {
                return existing
            }

            val generated = UUID.randomUUID().toString()
            prefs.edit { putString(CLIENT_ID, generated) }
            return generated
        }

    var serverHost: String
        get() = prefs.getString(SERVER_HOST, "")!!
        set(serverHost) = prefs.edit { putString(SERVER_HOST, serverHost) }

    var deviceToken: String
        get() = prefs.getString(DEVICE_TOKEN, "")!!
        set(deviceToken) = prefs.edit { putString(DEVICE_TOKEN, deviceToken) }

    val isEnrolled: Boolean
        get() = serverHost.isNotBlank() && deviceToken.isNotBlank()

    /** Host tried without a token on first launch, before falling back to a QR scan. */
    val wellKnownHost: String
        get() = BuildConfig.WELL_KNOWN_HOST

    var daysUntilTrash: Int
        get() = prefs.getInt(DAYS_UNTIL_TRASH, DEFAULT_DAYS_UNTIL_TRASH)
        set(daysUntilTrash) = prefs.edit { putInt(DAYS_UNTIL_TRASH, daysUntilTrash) }

    var daysUntilPurge: Int
        get() = prefs.getInt(DAYS_UNTIL_PURGE, DEFAULT_DAYS_UNTIL_PURGE)
        set(daysUntilPurge) = prefs.edit { putInt(DAYS_UNTIL_PURGE, daysUntilPurge) }

    fun applyEnrollment(host: String, enrollment: Enrollment) {
        serverHost = host
        deviceToken = enrollment.deviceToken
        applyServerFormat(enrollment.format)
        applyServerRetention(enrollment.retention)
    }

    fun applyServerFormat(format: ServerFormat) {
        extensionForCodec(format.codec)?.let { extension = it }
        bitrate = format.bitrate
        samplingRate = format.sampleRate
    }

    fun applyServerRetention(retention: ServerRetention) {
        daysUntilTrash = retention.daysUntilTrash
        daysUntilPurge = retention.daysUntilPurge
    }

    fun clearEnrollment() {
        serverHost = ""
        deviceToken = ""
    }

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEEP_SCREEN_ON, true)
        set(keepScreenOn) = prefs.edit().putBoolean(KEEP_SCREEN_ON, keepScreenOn).apply()
}
