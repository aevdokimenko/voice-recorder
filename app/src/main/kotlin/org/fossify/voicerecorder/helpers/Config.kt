package org.fossify.voicerecorder.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import androidx.core.content.edit
import org.fossify.commons.helpers.BaseConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.extensions.getDefaultRecordingsFolder

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    var saveRecordingsFolder: String
        get() = prefs.getString(SAVE_RECORDINGS, context.getDefaultRecordingsFolder())!!
        set(saveRecordingsFolder) = prefs.edit().putString(SAVE_RECORDINGS, saveRecordingsFolder)
            .apply()

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

    var useRecycleBin: Boolean
        get() = prefs.getBoolean(USE_RECYCLE_BIN, true)
        set(useRecycleBin) = prefs.edit().putBoolean(USE_RECYCLE_BIN, useRecycleBin).apply()

    var lastRecycleBinCheck: Long
        get() = prefs.getLong(LAST_RECYCLE_BIN_CHECK, 0L)
        set(lastRecycleBinCheck) = prefs.edit().putLong(LAST_RECYCLE_BIN_CHECK, lastRecycleBinCheck)
            .apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEEP_SCREEN_ON, true)
        set(keepScreenOn) = prefs.edit().putBoolean(KEEP_SCREEN_ON, keepScreenOn).apply()
}
