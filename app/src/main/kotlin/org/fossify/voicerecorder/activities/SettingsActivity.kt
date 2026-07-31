package org.fossify.voicerecorder.activities

import android.os.Bundle
import org.fossify.commons.dialogs.ChangeDateTimeFormatDialog
import org.fossify.commons.extensions.addLockedLabelIfNeeded
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.humanizePath
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.ActivitySettingsBinding
import org.fossify.voicerecorder.dialogs.MoveRecordingsDialog
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.hasRecordings
import org.fossify.voicerecorder.extensions.launchFolderPicker
import java.util.Locale
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomSystem = listOf(binding.settingsNestedScrollview))
        setupMaterialScrollListener(binding.settingsNestedScrollview, binding.settingsAppbar)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)

        setupUseEnglish()
        setupLanguage()
        setupChangeDateTimeFormat()
        setupSaveRecordingsFolder()
        setupKeepScreenOn()
        updateTextColors(binding.settingsNestedScrollview)

        binding.settingsGeneralSettingsLabel.setTextColor(getProperPrimaryColor())
    }

    private fun setupUseEnglish() {
        binding.settingsUseEnglishHolder.beVisibleIf(
            (config.wasUseEnglishToggled || Locale.getDefault().language != "en")
                    && !isTiramisuPlus()
        )
        binding.settingsUseEnglish.isChecked = config.useEnglish
        binding.settingsUseEnglishHolder.setOnClickListener {
            binding.settingsUseEnglish.toggle()
            config.useEnglish = binding.settingsUseEnglish.isChecked
            exitProcess(0)
        }
    }

    private fun setupLanguage() {
        binding.settingsLanguage.text = Locale.getDefault().displayLanguage
        if (isTiramisuPlus()) {
            binding.settingsLanguageHolder.beVisible()
            binding.settingsLanguageHolder.setOnClickListener {
                launchChangeAppLanguageIntent()
            }
        } else {
            binding.settingsLanguageHolder.beGone()
        }
    }

    private fun setupChangeDateTimeFormat() {
        binding.settingsChangeDateTimeFormatHolder.setOnClickListener {
            ChangeDateTimeFormatDialog(this) {}
        }
    }

    private fun setupSaveRecordingsFolder() {
        binding.settingsSaveRecordingsLabel.text =
            addLockedLabelIfNeeded(R.string.save_recordings_in)
        binding.settingsSaveRecordings.text = humanizePath(config.saveRecordingsFolder)
        binding.settingsSaveRecordingsHolder.setOnClickListener {
            val currentFolder = config.saveRecordingsFolder
            launchFolderPicker(currentFolder) { newFolder ->
                if (!newFolder.isNullOrEmpty()) {
                    ensureBackgroundThread {
                        val hasRecordings = hasRecordings()
                        runOnUiThread {
                            if (newFolder != currentFolder && hasRecordings) {
                                MoveRecordingsDialog(
                                    activity = this,
                                    previousFolder = currentFolder,
                                    newFolder = newFolder
                                ) {
                                    config.saveRecordingsFolder = newFolder
                                    binding.settingsSaveRecordings.text =
                                        humanizePath(config.saveRecordingsFolder)
                                }
                            } else {
                                config.saveRecordingsFolder = newFolder
                                binding.settingsSaveRecordings.text =
                                    humanizePath(config.saveRecordingsFolder)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupKeepScreenOn() {
        binding.settingsKeepScreenOn.isChecked = config.keepScreenOn
        binding.settingsKeepScreenOnHolder.setOnClickListener {
            binding.settingsKeepScreenOn.toggle()
            config.keepScreenOn = binding.settingsKeepScreenOn.isChecked
        }
    }
}
