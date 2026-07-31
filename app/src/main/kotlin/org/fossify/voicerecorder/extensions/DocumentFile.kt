package org.fossify.voicerecorder.extensions

import androidx.documentfile.provider.DocumentFile

fun DocumentFile.isAudioRecording(): Boolean {
    return type.isAudioMimeType() && !name.isNullOrEmpty() && !name!!.startsWith(".")
}
