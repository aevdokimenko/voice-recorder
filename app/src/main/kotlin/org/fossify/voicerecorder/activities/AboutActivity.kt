package org.fossify.voicerecorder.activities

import android.content.Intent
import android.os.Bundle
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.launchViewIntent
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.APP_LICENSES
import org.fossify.commons.helpers.LICENSE_AUDIO_RECORD_VIEW
import org.fossify.commons.helpers.LICENSE_AUTOFITTEXTVIEW
import org.fossify.commons.helpers.LICENSE_EVENT_BUS
import org.fossify.commons.helpers.LICENSE_GLIDE
import org.fossify.commons.helpers.LICENSE_GSON
import org.fossify.commons.helpers.LICENSE_JODA
import org.fossify.commons.helpers.LICENSE_PATTERN
import org.fossify.commons.helpers.LICENSE_REPRINT
import org.fossify.commons.helpers.LICENSE_RTL
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.ActivityAboutBinding

/**
 * Deliberately minimal: app identity plus the notices LR is legally obliged to carry as a
 * modified GPLv3 work that bundles third-party libraries. Commons' own AboutActivity is not
 * used — it adds rate/donate/invite/contributors/FAQ/email/social/more-apps entries that a
 * single-purpose internal tool has no use for.
 */
class AboutActivity : SimpleActivity() {

    private val licenseMask = LICENSE_EVENT_BUS or
            LICENSE_AUDIO_RECORD_VIEW or
            LICENSE_AUTOFITTEXTVIEW or
            LICENSE_GLIDE or
            LICENSE_JODA or
            LICENSE_GSON or
            LICENSE_RTL or
            LICENSE_PATTERN or
            LICENSE_REPRINT

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomSystem = listOf(binding.aboutNestedScrollview))
        setupMaterialScrollListener(binding.aboutNestedScrollview, binding.aboutAppbar)

        binding.aboutVersion.text = BuildConfig.VERSION_NAME

        binding.aboutSourceCodeHolder.setOnClickListener {
            launchViewIntent(getString(R.string.about_source_code_url))
        }

        binding.aboutLicensesHolder.setOnClickListener {
            Intent(applicationContext, org.fossify.commons.activities.LicenseActivity::class.java)
                .apply {
                    putExtra(APP_LICENSES, licenseMask)
                    startActivity(this)
                }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.aboutAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.aboutNestedScrollview)
        binding.aboutAppName.setTextColor(getProperPrimaryColor())
    }
}
