package org.fossify.voicerecorder.helpers

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 60_000
private const val UPLOAD_BUFFER_BYTES = 8 * 1024
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FLOOR = 500

sealed interface UploadResult {
    data object Success : UploadResult

    /** Transient — worth another attempt (offline, timeout, 5xx, 408, 429). */
    data class Retryable(val reason: String) : UploadResult

    /** The server rejected this in a way retrying will not fix (auth, bad request). */
    data class Permanent(val reason: String) : UploadResult
}

/**
 * PUTs [file] to a server-issued presigned [uploadUrl]. The URL carries its own authorization,
 * so no bearer header is sent. Streams the body so a long recording is never held in memory.
 * Transport security is plain TLS: this phase assumes a server the user operates and trusts.
 */
fun uploadRecording(file: File, uploadUrl: String): UploadResult {
    var connection: HttpURLConnection? = null

    return try {
        connection = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("Content-Type", mimeTypeForRecording(file.name))
        }

        file.inputStream().use { input ->
            connection.outputStream.use { output ->
                input.copyTo(output, UPLOAD_BUFFER_BYTES)
            }
        }

        classifyResponse(connection.responseCode)
    } catch (e: IOException) {
        UploadResult.Retryable(e.message ?: e::class.java.simpleName)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        UploadResult.Permanent(e.message ?: e::class.java.simpleName)
    } finally {
        connection?.disconnect()
    }
}

internal fun classifyResponse(code: Int): UploadResult = when {
    code in HttpURLConnection.HTTP_OK..HttpURLConnection.HTTP_PARTIAL -> UploadResult.Success
    code >= HTTP_SERVER_ERROR_FLOOR -> UploadResult.Retryable("HTTP $code")
    code == HttpURLConnection.HTTP_CLIENT_TIMEOUT -> UploadResult.Retryable("HTTP $code")
    code == HTTP_TOO_MANY_REQUESTS -> UploadResult.Retryable("HTTP $code")
    else -> UploadResult.Permanent("HTTP $code")
}

fun mimeTypeForRecording(fileName: String): String = when {
    fileName.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
    else -> "audio/mp4"
}
