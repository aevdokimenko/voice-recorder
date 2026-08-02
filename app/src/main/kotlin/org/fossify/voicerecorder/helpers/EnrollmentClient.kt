package org.fossify.voicerecorder.helpers

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The well-known probe runs on first launch, so it must not hold the UI up for long. */
private const val PROBE_CONNECT_TIMEOUT_MS = 3_000
private const val PROBE_READ_TIMEOUT_MS = 5_000
private const val API_CONNECT_TIMEOUT_MS = 15_000
private const val API_READ_TIMEOUT_MS = 30_000

private const val ENROLL_PATH = "/api/v1/enroll"
private const val CONFIG_PATH = "/api/v1/config"
private const val PRESIGN_PATH = "/api/v1/presign"

/**
 * Every call returns null on any failure — transport, status or payload. Callers treat that as
 * "not enrolled / cannot upload yet" and retry later, rather than distinguishing causes.
 */
object EnrollmentClient {

    fun enroll(
        host: String,
        clientId: String,
        deviceName: String,
        qrToken: String? = null,
        isProbe: Boolean = false
    ): Enrollment? {
        val body = JSONObject()
            .put("clientId", clientId)
            .put("deviceName", deviceName)
            .toString()

        val response = request(
            url = host.trimEnd('/') + ENROLL_PATH,
            method = "POST",
            token = qrToken,
            body = body,
            connectTimeout = if (isProbe) PROBE_CONNECT_TIMEOUT_MS else API_CONNECT_TIMEOUT_MS,
            readTimeout = if (isProbe) PROBE_READ_TIMEOUT_MS else API_READ_TIMEOUT_MS
        ) ?: return null

        return parseEnrollment(response)
    }

    fun fetchConfig(host: String, deviceToken: String): Pair<ServerFormat, ServerRetention>? {
        val response = request(
            url = host.trimEnd('/') + CONFIG_PATH,
            method = "GET",
            token = deviceToken,
            body = null
        ) ?: return null

        return parseConfig(response)
    }

    fun presign(
        host: String,
        deviceToken: String,
        filename: String,
        contentType: String,
        sizeBytes: Long
    ): String? {
        val body = JSONObject()
            .put("filename", filename)
            .put("contentType", contentType)
            .put("sizeBytes", sizeBytes)
            .toString()

        val response = request(
            url = host.trimEnd('/') + PRESIGN_PATH,
            method = "POST",
            token = deviceToken,
            body = body
        ) ?: return null

        return parsePresign(response)
    }

    @Suppress("LongParameterList")
    private fun request(
        url: String,
        method: String,
        token: String?,
        body: String?,
        connectTimeout: Int = API_CONNECT_TIMEOUT_MS,
        readTimeout: Int = API_READ_TIMEOUT_MS
    ): String? {
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                this.connectTimeout = connectTimeout
                this.readTimeout = readTimeout
                setRequestProperty("Accept", "application/json")
                if (token != null) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }

            body?.let { payload ->
                connection.outputStream.use { it.write(payload.toByteArray()) }
            }

            if (connection.responseCode !in HttpURLConnection.HTTP_OK..HttpURLConnection.HTTP_PARTIAL) {
                return null
            }

            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (@Suppress("SwallowedException") e: IOException) {
            null
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
