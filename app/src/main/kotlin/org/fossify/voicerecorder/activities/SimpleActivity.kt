package org.fossify.voicerecorder.activities

import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.helpers.REPOSITORY_NAME

open class SimpleActivity : BaseSimpleActivity() {
    override fun getAppIconIDs() = arrayListOf(R.mipmap.ic_launcher)

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = REPOSITORY_NAME
}
