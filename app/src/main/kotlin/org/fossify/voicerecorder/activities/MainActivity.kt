package org.fossify.voicerecorder.activities

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import me.grantland.widget.AutofitHelper
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.getBottomNavigationBackgroundColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.onPageChangeListener
import org.fossify.commons.extensions.onTabSelectionChanged
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateBottomTabItemColors
import org.fossify.commons.helpers.LICENSE_AUDIO_RECORD_VIEW
import org.fossify.commons.helpers.LICENSE_AUTOFITTEXTVIEW
import org.fossify.commons.helpers.LICENSE_EVENT_BUS
import org.fossify.commons.helpers.PERMISSION_RECORD_AUDIO
import org.fossify.commons.models.FAQItem
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.adapters.ViewPagerAdapter
import org.fossify.voicerecorder.databinding.ActivityMainBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.deleteExpiredTrashedRecordings
import org.fossify.voicerecorder.extensions.deleteTrashedRecordings
import org.fossify.voicerecorder.helpers.STOP_AMPLITUDE_UPDATE
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.services.RecorderService
import org.greenrobot.eventbus.EventBus

class MainActivity : SimpleActivity() {

    override var isSearchBarEnabled = true

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.mainTabsHolder))

        if (savedInstanceState == null) {
            deleteExpiredTrashedRecordings()
        }

        handlePermission(PERMISSION_RECORD_AUDIO) {
            if (it) {
                setupViewPager()
            } else {
                toast(org.fossify.commons.R.string.no_audio_permissions)
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateMenuColors()
        if (binding.viewPager.adapter == null) {
            setupViewPager()
        }
        setupTabColors()
        getPagerAdapter()?.onResume()
    }

    override fun onPause() {
        super.onPause()
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
    }

    override fun onDestroy() {
        super.onDestroy()
        getPagerAdapter()?.onDestroy()

        Intent(this@MainActivity, RecorderService::class.java).apply {
            action = STOP_AMPLITUDE_UPDATE
            try {
                startService(this)
            } catch (ignored: Exception) {
            }
        }
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else {
            false
        }
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.requireToolbar().inflateMenu(R.menu.menu)
        binding.mainMenu.toggleHideOnScroll(false)
        binding.mainMenu.setupMenu()

        binding.mainMenu.onSearchTextChangedListener = { text ->
            getPagerAdapter()?.searchTextChanged(text)
        }

        binding.mainMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                R.id.empty_recycle_bin -> confirmEmptyRecycleBin()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun updateOptionsMenuForCurrentTab() {
        val isTrashTabActive = binding.viewPager.currentItem == 1
        binding.mainMenu.requireToolbar().menu.findItem(R.id.empty_recycle_bin).isVisible = isTrashTabActive
    }

    private fun confirmEmptyRecycleBin() {
        org.fossify.commons.dialogs.ConfirmationDialog(
            activity = this,
            message = "",
            messageId = org.fossify.commons.R.string.empty_recycle_bin_confirmation,
            positive = org.fossify.commons.R.string.yes,
            negative = org.fossify.commons.R.string.no
        ) {
            org.fossify.commons.helpers.ensureBackgroundThread {
                deleteTrashedRecordings()
                runOnUiThread {
                    EventBus.getDefault().post(Events.RecordingTrashUpdated())
                }
            }
        }
    }

    private fun setupViewPager() {
        binding.mainTabsHolder.removeAllTabs()
        val tabDrawables = arrayOf(
            org.fossify.commons.R.drawable.ic_microphone_vector,
            org.fossify.commons.R.drawable.ic_delete_vector
        )
        val tabLabels = arrayOf(R.string.recordings, org.fossify.commons.R.string.recycle_bin)

        tabDrawables.forEachIndexed { i, drawableId ->
            binding.mainTabsHolder.newTab()
                .setCustomView(org.fossify.commons.R.layout.bottom_tablayout_item).apply {
                    customView
                        ?.findViewById<ImageView>(org.fossify.commons.R.id.tab_item_icon)
                        ?.setImageDrawable(
                            AppCompatResources.getDrawable(
                                this@MainActivity,
                                drawableId
                            )
                        )

                    customView
                        ?.findViewById<TextView>(org.fossify.commons.R.id.tab_item_label)
                        ?.setText(tabLabels[i])

                    AutofitHelper.create(
                        customView?.findViewById(org.fossify.commons.R.id.tab_item_label)
                    )

                    binding.mainTabsHolder.addTab(this)
                }
        }

        binding.mainTabsHolder.onTabSelectionChanged(
            tabUnselectedAction = {
                updateBottomTabItemColors(it.customView, false)
                binding.mainMenu.closeSearch()
            },
            tabSelectedAction = {
                binding.viewPager.currentItem = it.position
                updateBottomTabItemColors(it.customView, true)
            }
        )

        binding.viewPager.adapter = ViewPagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 1
        binding.viewPager.onPageChangeListener {
            binding.mainTabsHolder.getTabAt(it)?.select()
            (binding.viewPager.adapter as ViewPagerAdapter).finishActMode()
            updateOptionsMenuForCurrentTab()
        }

        binding.viewPager.currentItem = config.lastUsedViewPagerPage
        binding.mainTabsHolder.getTabAt(config.lastUsedViewPagerPage)?.select()
        updateOptionsMenuForCurrentTab()
    }

    private fun setupTabColors() {
        val activeView = binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.customView
        updateBottomTabItemColors(activeView, true)
        for (i in 0 until binding.mainTabsHolder.tabCount) {
            if (i != binding.viewPager.currentItem) {
                val inactiveView = binding.mainTabsHolder.getTabAt(i)?.customView
                updateBottomTabItemColors(inactiveView, false)
            }
        }

        binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.select()
        val bottomBarColor = getBottomNavigationBackgroundColor()
        binding.mainTabsHolder.setBackgroundColor(bottomBarColor)
    }

    private fun getPagerAdapter() = (binding.viewPager.adapter as? ViewPagerAdapter)

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_EVENT_BUS or
                LICENSE_AUDIO_RECORD_VIEW or
                LICENSE_AUTOFITTEXTVIEW

        val faqItems = arrayListOf(
            FAQItem(
                title = R.string.based_on_fossify_voice_recorder,
                text = R.string.based_on_fossify_voice_recorder
            ),
            FAQItem(
                title = R.string.faq_1_title,
                text = R.string.faq_1_text
            ),
            FAQItem(
                title = org.fossify.commons.R.string.faq_9_title_commons,
                text = org.fossify.commons.R.string.faq_9_text_commons
            )
        )

        startAboutActivity(
            appNameId = R.string.app_name,
            licenseMask = licenses,
            versionName = BuildConfig.VERSION_NAME,
            faqItems = faqItems,
            showFAQBeforeMail = true
        )
    }

}
