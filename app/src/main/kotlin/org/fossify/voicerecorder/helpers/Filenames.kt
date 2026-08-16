package org.fossify.voicerecorder.helpers

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val RECORDING_FILENAME_PATTERN = "yyyyMMdd_HHmmss"

fun generateRecordingFilename(now: Calendar = Calendar.getInstance()): String {
    val formatter = SimpleDateFormat(RECORDING_FILENAME_PATTERN, Locale.ROOT)
    formatter.timeZone = now.timeZone
    return formatter.format(now.time)
}
