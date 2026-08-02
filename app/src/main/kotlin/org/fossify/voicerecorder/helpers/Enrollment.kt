package org.fossify.voicerecorder.helpers

import org.json.JSONObject

const val DEFAULT_DAYS_UNTIL_TRASH = 15
const val DEFAULT_DAYS_UNTIL_PURGE = 30

const val CODEC_AAC = "aac"
const val CODEC_OPUS = "opus"

private const val ENROLL_PATH = "/api/v1/enroll"

data class ServerFormat(val codec: String, val bitrate: Int, val sampleRate: Int)

data class ServerRetention(val daysUntilTrash: Int, val daysUntilPurge: Int)

data class Enrollment(
    val deviceToken: String,
    val format: ServerFormat,
    val retention: ServerRetention
)

/** Maps a server codec name onto the app's extension constants, or null if unsupported. */
fun extensionForCodec(codec: String): Int? = when (codec.lowercase()) {
    CODEC_AAC -> EXTENSION_M4A
    CODEC_OPUS -> EXTENSION_OGG
    else -> null
}

/**
 * All of these return null rather than throwing: a response we cannot fully understand is
 * treated as a failed enrollment, so the app falls back to asking the user to scan again
 * instead of half-configuring itself.
 */
fun parseEnrollment(body: String): Enrollment? = runCatchingJson {
    val json = JSONObject(body)
    val deviceToken = json.optString("deviceToken").takeIf { it.isNotBlank() } ?: return@runCatchingJson null
    val format = parseFormat(json.optJSONObject("format")) ?: return@runCatchingJson null
    Enrollment(deviceToken, format, parseRetention(json.optJSONObject("retention")))
}

fun parseConfig(body: String): Pair<ServerFormat, ServerRetention>? = runCatchingJson {
    val json = JSONObject(body)
    val format = parseFormat(json.optJSONObject("format")) ?: return@runCatchingJson null
    format to parseRetention(json.optJSONObject("retention"))
}

fun parsePresign(body: String): String? = runCatchingJson {
    JSONObject(body).optString("uploadUrl").takeIf { it.isNotBlank() }
}

/** Strips the enroll path off a QR payload, leaving the scheme+host+port to build other calls from. */
fun enrollHostFrom(qrPayload: String): String? {
    val trimmed = qrPayload.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return null
    }

    val withoutQuery = trimmed.substringBefore('?')
    return withoutQuery.removeSuffix("/").removeSuffix(ENROLL_PATH).removeSuffix("/")
        .takeIf { it.isNotBlank() }
}

fun enrollTokenFrom(qrPayload: String): String? = qrPayload
    .substringAfter("token=", "")
    .substringBefore('&')
    .takeIf { it.isNotBlank() }

private fun parseFormat(json: JSONObject?): ServerFormat? {
    if (json == null) {
        return null
    }

    val codec = json.optString("codec")
    if (extensionForCodec(codec) == null) {
        return null
    }

    return ServerFormat(
        codec = codec.lowercase(),
        bitrate = json.optInt("bitrate", DEFAULT_BITRATE),
        sampleRate = json.optInt("sampleRate", DEFAULT_SAMPLING_RATE)
    )
}

private fun parseRetention(json: JSONObject?) = ServerRetention(
    daysUntilTrash = json?.optInt("daysUntilTrash", DEFAULT_DAYS_UNTIL_TRASH)
        ?: DEFAULT_DAYS_UNTIL_TRASH,
    daysUntilPurge = json?.optInt("daysUntilPurge", DEFAULT_DAYS_UNTIL_PURGE)
        ?: DEFAULT_DAYS_UNTIL_PURGE
)

private inline fun <T> runCatchingJson(block: () -> T?): T? = try {
    block()
} catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
    null
}
