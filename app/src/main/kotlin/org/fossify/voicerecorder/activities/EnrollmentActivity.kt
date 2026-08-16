package org.fossify.voicerecorder.activities

import android.app.Activity
import android.os.Build
import android.os.Bundle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.PERMISSION_CAMERA
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.ActivityEnrollmentBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.EnrollmentClient
import org.fossify.voicerecorder.helpers.enrollHostFrom
import org.fossify.voicerecorder.helpers.enrollTokenFrom

/**
 * Connects this install to a server. Tries the built-in well-known host first — that covers
 * devices on a trusted network — and only asks for a QR scan when that fails. There is
 * deliberately no manual URL entry.
 */
class EnrollmentActivity : SimpleActivity() {

    private lateinit var binding: ActivityEnrollmentBinding

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) {
            showScanPrompt()
        } else {
            enrollFromQr(contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.enrollmentHolder))

        updateTextColors(binding.enrollmentHolder)
        binding.enrollmentScanButton.setOnClickListener { startScan() }

        if (config.isEnrolled) {
            finishEnrolled()
        } else {
            probeWellKnownHost()
        }
    }

    private fun probeWellKnownHost() {
        val host = config.wellKnownHost
        if (host.isBlank()) {
            showScanPrompt()
            return
        }

        binding.enrollmentProgress.beVisible()
        ensureBackgroundThread {
            val enrollment = EnrollmentClient.enroll(
                host = host,
                clientId = config.clientId,
                deviceName = Build.MODEL ?: "android",
                isProbe = true
            )

            runOnUiThread {
                if (enrollment == null) {
                    showScanPrompt()
                } else {
                    config.applyEnrollment(host, enrollment)
                    finishEnrolled()
                }
            }
        }
    }

    private fun showScanPrompt() {
        binding.enrollmentProgress.beGone()
        binding.enrollmentMessage.beVisible()
        binding.enrollmentScanButton.beVisible()
    }

    private fun startScan() {
        handlePermission(PERMISSION_CAMERA) { granted ->
            if (granted) {
                val options = ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.enrollment_scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                scanLauncher.launch(options)
            } else {
                toast(org.fossify.commons.R.string.no_camera_permissions)
            }
        }
    }

    private fun enrollFromQr(payload: String) {
        val host = enrollHostFrom(payload)
        if (host == null) {
            toast(R.string.enrollment_invalid_qr)
            showScanPrompt()
            return
        }

        binding.enrollmentMessage.beGone()
        binding.enrollmentScanButton.beGone()
        binding.enrollmentProgress.beVisible()

        ensureBackgroundThread {
            val enrollment = EnrollmentClient.enroll(
                host = host,
                clientId = config.clientId,
                deviceName = Build.MODEL ?: "android",
                qrToken = enrollTokenFrom(payload)
            )

            runOnUiThread {
                if (enrollment == null) {
                    toast(R.string.enrollment_failed)
                    showScanPrompt()
                } else {
                    config.applyEnrollment(host, enrollment)
                    toast(R.string.enrollment_succeeded)
                    finishEnrolled()
                }
            }
        }
    }

    private fun finishEnrolled() {
        setResult(Activity.RESULT_OK)
        finish()
    }
}
