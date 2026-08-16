package org.fossify.voicerecorder.models

import org.fossify.voicerecorder.helpers.UploadState

data class Recording(
    val id: Int,
    val title: String,
    val path: String,
    val timestamp: Long,
    val duration: Int,
    val size: Int,
    val uploadState: UploadState? = null
)
