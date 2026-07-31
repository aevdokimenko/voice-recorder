# LR — Rebrand & Strip Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebrand Fossify Voice Recorder as "LR" under a new package id and single build, and strip every UI element, setting, and code path not needed for the meeting-recorder workflow (record → optionally rename → delete), leaving a fully working, testable app with a merged Recorder+list screen and an always-on Recycle Bin. Endpoint enrollment, sidecar upload-status tracking, and the upload worker are **out of scope** for this plan — they are separate follow-up plans per the design spec.

**Architecture:** No new architecture in this plan — it is a subtractive/rebranding pass over the existing Fossify Voice Recorder codebase (EventBus-driven `RecorderService`, SAF/File storage split by SDK level, `PagerAdapter`-based tabs). The one structural change is merging `RecorderFragment` + `PlayerFragment` into a single `RecordingsFragment` and collapsing the pager from a conditional 2-or-3 tabs to a fixed 2 tabs (Recordings, Recycle Bin).

**Tech Stack:** Kotlin, Android views + view binding (no Compose), `org.fossify:commons`, greenrobot EventBus, JUnit 4 (newly introduced in this plan for one pure-logic unit test).

**Spec:** `docs/superpowers/specs/2026-07-29-lr-meeting-recorder-design.md`

## Global Constraints

- Package id: `ai.lequipe.lr`. App name: `LR`.
- Java 17, minSdk 26, compileSdk 36 (unchanged).
- Detekt must stay clean: `maxIssues = 0`, max line length 120, `MagicNumber` free values are only `-1, 0, 1, 2, 42, 1000` — use named constants for anything else. Do not touch `app/detekt-baseline.xml` or `app/lint-baseline.xml`.
- No existing `src/test` or `src/androidTest` in this repo today — Task 8 introduces the first one. Don't claim tests pass without running them.
- GPLv3 (`LICENSE`) stays as-is; the About screen must credit Fossify Voice Recorder and link to this fork (Task 13).
- Recycle bin is always on (no more `useRecycleBin` toggle) — "delete" from the main list always means trash, never immediate permanent delete.
- Commits: Conventional Commits style (`feat:`, `fix:`, `chore:`, `refactor:`), one per task step group as shown below.

---

### Task 1: Rebrand identifiers (package id, app name, version)

**Files:**
- Modify: `gradle.properties:25-27`
- Modify: `app/build.gradle.kts:25-28`
- Modify: `app/src/main/res/values/donottranslate.xml:2-3`
- Modify: `app/src/main/res/values/strings.xml:3`
- Modify: `settings.gradle.kts:16`

**Interfaces:**
- Produces: applicationId `ai.lequipe.lr` used by all later tasks; app display name "LR" used by Task 13's About text.

- [x] **Step 1: Update the package id and version in `gradle.properties`**

```properties
# Versioning
VERSION_NAME=1.0.0
VERSION_CODE=1
APP_ID=ai.lequipe.lr
```

- [x] **Step 2: Update the archives name in `app/build.gradle.kts`**

Note: `namespace` at `app/build.gradle.kts:116` was also decoupled from `APP_ID` (hardcoded to
`org.fossify.voicerecorder`) — it was previously `namespace = project.property("APP_ID").toString()`,
and since the plan keeps all Kotlin sources under the `org.fossify.voicerecorder` package throughout,
changing `APP_ID` alone broke implicit `R` resolution across most files.

```kotlin
base {
    val versionCode = project.property("VERSION_CODE").toString().toInt()
    archivesName = "lr-$versionCode"
}
```

- [x] **Step 3: Rebrand the non-translatable app name / package name strings**

Edit `app/src/main/res/values/donottranslate.xml`:

```xml
<resources>
    <string name="package_name">ai.lequipe.lr</string>
    <string name="app_name">LR</string>
    <string name="m4a">m4a</string>
    <string name="mp3">mp3</string>
    <string name="mp3_experimental">mp3 (Experimental)</string>
    <string name="ogg">ogg</string>
    <string name="ogg_opus">ogg (Opus)</string>
    <string name="bitrate_value">%d kbps</string>
    <string name="sampling_rate_value">%d Hz</string>
</resources>
```

(The `mp3*` and `bitrate_value`/`sampling_rate_value` entries are cleaned up in Tasks 7 and 9 — leave them for now so this step is a pure rename.)

- [x] **Step 4: Rebrand the launcher name in `app/src/main/res/values/strings.xml`**

```xml
    <string name="app_launcher_name">LR</string>
```

- [x] **Step 5: Rename the Gradle root project**

Edit `settings.gradle.kts` line 16:

```kotlin
rootProject.name = "LR"
```

- [x] **Step 6: Update CLAUDE.md's project/commands references**

Edit `CLAUDE.md`: change `package org.fossify.voicerecorder` to `package ai.lequipe.lr` in the Project section, and change the flavor-suffixed Gradle command examples (`assembleCoreDebug`, `installCoreDebug`) to unsuffixed ones (`assembleDebug`, `installDebug`) — the flavor dimension is removed in Task 2, so those examples become wrong otherwise.

- [x] **Step 7: Build and verify the new identifiers took effect**

Run: `./gradlew assembleDebug` (requires JDK 17 on `PATH`/`JAVA_HOME` per `CLAUDE.md`)
Expected: build succeeds; `app/build/outputs/apk/debug/app-debug.apk` exists.

Run: `unzip -p app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml | strings | grep -i "ai.lequipe.lr"`
Expected: the new package id appears in the built manifest.

Note: the three build flavors (`core`/`foss`/`gplay`, removed in Task 2) still existed at this point,
so outputs landed at `app/build/outputs/apk/<flavor>/debug/lr-1-<flavor>-debug.apk` rather than the
plan's single-flavor path. `aapt dump badging` on the `core` debug APK confirmed
`package: name='ai.lequipe.lr.debug' versionCode='1' versionName='1.0.0'` (`strings | grep` on the raw
binary manifest XML doesn't reliably find the UTF-16-encoded package string, so `aapt dump badging` was
used instead). `detekt` was also run and passed clean.

- [x] **Step 8: Commit**

```bash
git add gradle.properties app/build.gradle.kts app/src/main/res/values/donottranslate.xml \
  app/src/main/res/values/strings.xml settings.gradle.kts CLAUDE.md
git commit -m "chore: rebrand to LR (ai.lequipe.lr)"
```

---

### Task 2: Collapse to a single build flavor, drop cross-promotion

**Files:**
- Modify: `app/build.gradle.kts:84-89`
- Delete: `app/src/foss/` (entire directory)
- Delete: `app/src/gplay/` (entire directory)
- Modify: `app/src/main/res/menu/menu.xml:16-19`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt:130-136,153-161`

**Interfaces:**
- Produces: a single build variant (`debug`/`release` only, no flavor prefix) — later tasks and CLAUDE.md's Gradle command examples assume this.

- [x] **Step 1: Remove the flavor dimension from `app/build.gradle.kts`**

Delete lines 84-89 (`flavorDimensions.add("variants")` through the closing `}` of `productFlavors`):

```kotlin
    // (flavorDimensions.add("variants") / productFlavors block removed entirely)
```

- [x] **Step 2: Delete the foss and gplay source sets**

```bash
git rm -r app/src/foss app/src/gplay
```

- [x] **Step 3: Remove "More Fossify apps" from the options menu**

Edit `app/src/main/res/menu/menu.xml`, delete the `more_apps_from_us` `<item>` (lines 16-19), leaving only `settings` and `about`.

- [x] **Step 4: Remove the cross-promotion menu wiring in `MainActivity`**

`refreshMenuItems()` (lines 130-136) exists solely to toggle the now-deleted `more_apps_from_us` item's visibility, so both it and its call site go away. Edit `onCreate()` (lines 55-56):

```kotlin
        setupOptionsMenu()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.mainTabsHolder))
```

(dropping the `refreshMenuItems()` line that used to follow `setupOptionsMenu()`), and delete the `refreshMenuItems()` method (lines 130-136) entirely.

Update `setupOptionsMenu()`'s click handler (lines 153-161) to drop the `more_apps_from_us` branch and its now-unused import:

```kotlin
        binding.mainMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
```

Remove the now-unused `import org.fossify.commons.extensions.launchMoreAppsFromUsIntent` from the top of the file.

- [x] **Step 5: Build and verify only one variant exists**

Run: `./gradlew tasks --group build`
Expected: `assembleDebug` / `assembleRelease` are listed with no `Core`/`Foss`/`Gplay` variants.

Run: `./gradlew assembleDebug detekt`
Expected: both succeed.

- [x] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/res/menu/menu.xml \
  app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt
git rm -r app/src/foss app/src/gplay
git commit -m "chore: collapse to a single build flavor, drop cross-promotion menu"
```

---

### Task 3: Remove themed launcher icon aliases and the color-customization link

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:66-69,102-107,120-383`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/activities/SimpleActivity.kt`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/activities/SettingsActivity.kt:65,93-97`
- Modify: `app/src/main/res/layout/activity_settings.xml:38-81`

**Interfaces:**
- Produces: `SplashActivity` is the sole launcher entry point (its own `<intent-filter>` carries `MAIN`/`LAUNCHER` directly instead of via an alias).

- [x] **Step 1: Move the launcher intent-filter onto `SplashActivity` itself and delete all 19 aliases**

Edit `app/src/main/AndroidManifest.xml`. First, change the `SplashActivity` declaration (lines 66-69) to carry the launcher intent-filter:

```xml
        <activity
            android:name=".activities.SplashActivity"
            android:exported="true"
            android:theme="@style/SplashTheme">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
```

Then delete all 19 `<activity-alias android:name=".activities.SplashActivity.*">` blocks (lines 120-383, from the first `Red` alias through the last `Grey_black` alias) — nothing else in the manifest depends on them.

Also delete the `CustomizationActivity` declaration (lines 102-107):

```xml
        <!-- (org.fossify.commons.activities.CustomizationActivity entry removed) -->
```

- [x] **Step 2: Trim `SimpleActivity.getAppIconIDs()` to the single remaining icon**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/activities/SimpleActivity.kt`:

```kotlin
package org.fossify.voicerecorder.activities

import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.helpers.REPOSITORY_NAME

open class SimpleActivity : BaseSimpleActivity() {
    override fun getAppIconIDs() = arrayListOf(R.mipmap.ic_launcher)

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = REPOSITORY_NAME
}
```

(`getAppIconIDs()` stays overridden — it's abstract on `BaseSimpleActivity` — just trimmed to one entry.)

- [x] **Step 2b: Note the update-check/GitHub-link caveat**

`REPOSITORY_NAME` (`Voice-Recorder`) is consumed by `org.fossify:commons`' `getRepositoryName()`-based features (e.g. any GitHub-link/update-check UI commons builds from it). This repo's actual location is `github.com/aevdokimenko/voice-recorder`, not `FossifyOrg/Voice-Recorder`, and commons' internals aren't visible from this codebase to know whether it hardcodes the `FossifyOrg` org alongside the repo name. Flag this rather than guess: if such a feature surfaces during manual testing in Task 14 (e.g. a broken "view on GitHub" link), file it as a follow-up rather than patching commons internals blind.

- [x] **Step 3: Remove the color-customization row and its click handler from Settings**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/activities/SettingsActivity.kt`: remove the `setupCustomizeColors()` call from `onResume()` (line 65) and delete the method itself (lines 93-97):

```kotlin
    // (setupCustomizeColors() and its call site removed)
```

- [x] **Step 4: Remove the color-customization section from the Settings layout**

Edit `app/src/main/res/layout/activity_settings.xml`: delete the `settings_color_customization_section_label` `<TextView>`, the `settings_color_customization_holder` `<ConstraintLayout>`, and the `settings_color_customization_divider` `<include>` (lines 38-81 up to but not including the widget-color-customization block, which Task 4 removes separately).

Also remove `settings_color_customization_section_label` from the color-tinting array in `SettingsActivity.onResume()` (part of the `arrayOf(...).forEach { it.setTextColor(...) }` block) — it no longer exists in the layout.

- [x] **Step 5: Build and manually verify the launcher icon**

Run: `./gradlew installDebug`
Expected: install succeeds; a single "LR" launcher icon appears (no themed-icon entries in the launcher's icon-shortcut long-press menu).

Note: `./gradlew assembleDebug detekt` both passed clean (JDK 17 via `/opt/homebrew/opt/openjdk@17`,
not on `PATH` by default in this environment). `installDebug`/on-device verification was skipped —
no adb or connected device/emulator is available in this environment.

- [x] **Step 6: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
  app/src/main/kotlin/org/fossify/voicerecorder/activities/SimpleActivity.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/activities/SettingsActivity.kt \
  app/src/main/res/layout/activity_settings.xml
git commit -m "chore: remove themed launcher icons and color-customization screen"
```

---

### Task 4: Remove widget color customization

**Files:**
- Delete: `app/src/main/kotlin/org/fossify/voicerecorder/activities/WidgetRecordDisplayConfigureActivity.kt`
- Delete: `app/src/main/res/layout/widget_record_display_config.xml`
- Modify: `app/src/main/AndroidManifest.xml:37-46`
- Modify: `app/src/main/res/xml/widget_record_display.xml`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/MyWidgetRecordDisplayProvider.kt`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/activities/SettingsActivity.kt:66,99-106`
- Modify: `app/src/main/res/layout/activity_settings.xml` (widget-color-customization block + purchase-thank-you row)

**Interfaces:**
- Produces: the widget still starts/stops recording and shows a fixed accent color while recording (no longer user-configurable).

- [x] **Step 1: Delete the widget configure activity and its layout**

```bash
git rm app/src/main/kotlin/org/fossify/voicerecorder/activities/WidgetRecordDisplayConfigureActivity.kt
git rm app/src/main/res/layout/widget_record_display_config.xml
```

- [x] **Step 2: Remove the configure-activity manifest entry and the widget's `configure` attribute**

Edit `app/src/main/AndroidManifest.xml`, delete the `WidgetRecordDisplayConfigureActivity` `<activity>` block (lines 37-46).

Edit `app/src/main/res/xml/widget_record_display.xml`, drop `android:configure`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:initialLayout="@layout/widget_record_display"
    android:minWidth="40dp"
    android:minHeight="40dp"
    android:previewImage="@drawable/ic_microphone_widget_icon"
    android:updatePeriodMillis="86400000" />
```

- [x] **Step 3: Replace the customizable widget color with a fixed accent color**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/helpers/MyWidgetRecordDisplayProvider.kt`:

```kotlin
package org.fossify.voicerecorder.helpers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.RemoteViews
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.BackgroundRecordActivity
import org.fossify.voicerecorder.extensions.drawableToBitmap

class MyWidgetRecordDisplayProvider : AppWidgetProvider() {
    companion object {
        private const val OPEN_APP_INTENT_ID = 1
        private const val RECORDING_COLOR = 0xFFE53935.toInt()
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        changeWidgetIcon(appWidgetManager, context, Color.WHITE)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TOGGLE_WIDGET_UI && intent.extras?.containsKey(IS_RECORDING) == true) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val color = if (intent.extras!!.getBoolean(IS_RECORDING)) {
                RECORDING_COLOR
            } else {
                Color.WHITE
            }

            changeWidgetIcon(appWidgetManager, context, color)
        } else {
            super.onReceive(context, intent)
        }
    }

    private fun changeWidgetIcon(appWidgetManager: AppWidgetManager, context: Context, color: Int) {
        val bmp = getColoredIcon(context, color)

        appWidgetManager.getAppWidgetIds(getComponentName(context)).forEach {
            RemoteViews(context.packageName, R.layout.widget_record_display).apply {
                setupAppOpenIntent(context, this)
                setImageViewBitmap(R.id.record_display_btn, bmp)
                appWidgetManager.updateAppWidget(it, this)
            }
        }
    }

    private fun getComponentName(context: Context): ComponentName {
        return ComponentName(context, MyWidgetRecordDisplayProvider::class.java)
    }

    private fun setupAppOpenIntent(context: Context, views: RemoteViews) {
        Intent(context, BackgroundRecordActivity::class.java).apply {
            action = BackgroundRecordActivity.RECORD_INTENT_ACTION
            val pendingIntent = PendingIntent.getActivity(
                context,
                OPEN_APP_INTENT_ID,
                this,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.record_display_btn, pendingIntent)
        }
    }

    private fun getColoredIcon(context: Context, color: Int): Bitmap {
        val drawable = context.resources.getColoredDrawableWithColor(
            drawableId = org.fossify.commons.R.drawable.ic_microphone_vector,
            color = color
        )
        return context.drawableToBitmap(drawable)
    }
}
```

(`RECORDING_COLOR` is a literal ARGB constant, so it needs `@Suppress("MagicNumber")` per detekt's rules — add `@file:Suppress("MagicNumber")` at the top of the file, above the `package` line.)

- [x] **Step 4: Remove the widget-color-customization row and the purchase-thank-you row from Settings**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/activities/SettingsActivity.kt`: remove the `setupCustomizeWidgetColors()` call from `onResume()` (line 66) and delete the method (lines 99-106). Remove the now-unused `IS_CUSTOMIZING_COLORS` import and the `WidgetRecordDisplayConfigureActivity` reference is gone along with it.

Edit `app/src/main/res/layout/activity_settings.xml`: delete the `settings_widget_color_customization_holder` `<ConstraintLayout>` block, and delete the `settings_purchase_thank_you_holder` `<org.fossify.commons.views.PurchaseThankYouItem>` row (it existed only to unlock the widget-color feature we just removed).

- [x] **Step 5: Build and manually verify the widget**

Run: `./gradlew installDebug`
Expected: build succeeds. Manually add the LR widget to a home screen, tap it to start a recording (should turn red), tap again to stop (should turn white) — no color-picker screen should appear anywhere.

Note: `./gradlew assembleDebug detekt` both passed clean. `installDebug`/on-device widget
verification (skipped - not automatable): no adb or connected device/emulator available in this
environment.

- [x] **Step 6: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/widget_record_display.xml \
  app/src/main/kotlin/org/fossify/voicerecorder/helpers/MyWidgetRecordDisplayProvider.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/activities/SettingsActivity.kt \
  app/src/main/res/layout/activity_settings.xml
git rm app/src/main/kotlin/org/fossify/voicerecorder/activities/WidgetRecordDisplayConfigureActivity.kt
git rm app/src/main/res/layout/widget_record_display_config.xml
git commit -m "chore: remove widget color customization, keep fixed recording-state color"
```

---

### Task 5: Remove third-party RECORD_SOUND_ACTION handling

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:76-79`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt`

**Interfaces:**
- Produces: `MainActivity` no longer responds to `android.provider.MediaStore.RECORD_SOUND`; it is a normal launcher-only activity as far as intents are concerned.

- [x] **Step 1: Remove the intent-filter from the manifest**

Edit `app/src/main/AndroidManifest.xml`, change the `MainActivity` declaration (lines 71-80) to drop its `<intent-filter>`:

```xml
        <activity
            android:name=".activities.MainActivity"
            android:configChanges="orientation|screenSize"
            android:exported="true"
            android:launchMode="singleTask" />
```

- [x] **Step 2: Remove `isThirdPartyIntent()` and its call sites in `MainActivity`**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt`:

Change `onBackPressedCompat()`:

```kotlin
    override fun onBackPressedCompat(): Boolean {
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else {
            false
        }
    }
```

In `setupViewPager()`, remove the `isThirdPartyIntent()` branch (this becomes Task 11's concern too since the method also lives in `setupViewPager()`, but do the third-party-specific part now):

```kotlin
        binding.viewPager.currentItem = config.lastUsedViewPagerPage
        binding.mainTabsHolder.getTabAt(config.lastUsedViewPagerPage)?.select()
```

(replacing the `if (isThirdPartyIntent()) { ... } else { ... }` block).

Delete the `isThirdPartyIntent()` method and the `recordingSaved()` `@Subscribe` method entirely:

```kotlin
    // (isThirdPartyIntent() and recordingSaved(event: Events.RecordingSaved) removed)
```

Remove the now-unused `import android.provider.MediaStore` and `import org.fossify.voicerecorder.models.Events` if nothing else in the file references `Events` (check with a grep before removing the import).

- [x] **Step 3: Build and verify**

Run: `grep -n "Events\." app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt`
Expected: no matches — confirms the `Events` import is safe to remove.

Run: `./gradlew assembleDebug detekt`
Expected: both succeed.

- [x] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
  app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt
git commit -m "chore: remove third-party RECORD_SOUND_ACTION handling"
```

---

### Task 6: Remove legacy MediaStore-trashed compatibility

**Files:**
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/extensions/Context.kt:109-123,140-160`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/extensions/DocumentFile.kt`

**Interfaces:**
- Produces: `Context.getAllRecordings(trashed: Boolean = false): ArrayList<Recording>` — unchanged signature, simplified body.

- [x] **Step 1: Simplify `getAllRecordings()` and delete the legacy function**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/extensions/Context.kt`:

```kotlin
fun Context.getAllRecordings(trashed: Boolean = false): ArrayList<Recording> {
    return if (isRPlus()) {
        getRecordings(trashed)
    } else {
        getLegacyRecordings(trashed)
    }
}
```

Delete the `getMediaStoreTrashedRecordings()` function (lines 140-160) entirely.

- [x] **Step 2: Remove the now-unused `isTrashedMediaStoreRecording()` helper**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/extensions/DocumentFile.kt`:

```kotlin
package org.fossify.voicerecorder.extensions

import androidx.documentfile.provider.DocumentFile

fun DocumentFile.isAudioRecording(): Boolean {
    return type.isAudioMimeType() && !name.isNullOrEmpty() && !name!!.startsWith(".")
}
```

- [x] **Step 3: Build and verify**

Run: `./gradlew assembleDebug detekt`
Expected: both succeed (no remaining references to the deleted function/property).

- [x] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/fossify/voicerecorder/extensions/Context.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/extensions/DocumentFile.kt
git commit -m "chore: remove legacy MediaStore-trashed-recording compatibility"
```

---

### Task 7: Drop mp3 recording (AndroidLame) entirely

**Files:**
- Delete: `app/src/main/kotlin/org/fossify/voicerecorder/recorder/Mp3Recorder.kt`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/services/RecorderService.kt`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt:15-42,59-69,81-85`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt:53-67`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt` (license mask)
- Modify: `app/src/main/res/values/donottranslate.xml`
- Modify: `gradle/libs.versions.toml:20-21,45-46`
- Modify: `app/build.gradle.kts:147`

**Interfaces:**
- Produces: `Config.getExtension(): String` now only returns `"m4a"` or `"ogg"`.

- [x] **Step 1: Delete `Mp3Recorder`**

```bash
git rm app/src/main/kotlin/org/fossify/voicerecorder/recorder/Mp3Recorder.kt
```

- [x] **Step 2: Always use `MediaRecorderWrapper` in `RecorderService`**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/services/RecorderService.kt`. Replace:

```kotlin
            recorder = if (recordMp3()) {
                Mp3Recorder(this)
            } else {
                MediaRecorderWrapper(this)
            }
```

with:

```kotlin
            recorder = MediaRecorderWrapper(this)
```

Delete the `recordMp3()` method:

```kotlin
    // (recordMp3() removed)
```

Remove the now-unused imports `org.fossify.voicerecorder.helpers.EXTENSION_MP3` and `org.fossify.voicerecorder.recorder.Mp3Recorder`.

- [x] **Step 3: Remove mp3 constants from `Constants.kt`**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt`. Replace lines 15-42:

```kotlin
const val EXTENSION_M4A = 0
const val EXTENSION_OGG = 2

val BITRATES_M4A = arrayListOf(
    8000, 14000, 24000, 28000, 32000, 64000, 96000, 128000, 160000, 192000, 288000
)
val BITRATES_OPUS = arrayListOf(
    8000, 16000, 24000, 32000, 64000, 96000, 128000, 160000, 192000, 256000, 320000
)
val BITRATES = mapOf(
    EXTENSION_M4A to BITRATES_M4A,
    EXTENSION_OGG to BITRATES_OPUS
)
const val DEFAULT_BITRATE = 96000

val SAMPLING_RATES_M4A = arrayListOf(11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)
val SAMPLING_RATES_OPUS = arrayListOf(8000, 12000, 16000, 24000, 48000)
val SAMPLING_RATES = mapOf(
    EXTENSION_M4A to SAMPLING_RATES_M4A,
    EXTENSION_OGG to SAMPLING_RATES_OPUS
)
const val DEFAULT_SAMPLING_RATE = 48000
```

(`EXTENSION_MP3` and `BITRATES_MP3`/`SAMPLING_RATES_MP3` are gone; `EXTENSION_OGG` keeps its value `2` so `EXTENSION` preference values already stored on real devices from the original app don't silently remap to a different format — irrelevant here since this is a fresh package id with no upgrade path, but it costs nothing to keep the value stable.)

Replace lines 59-69 (`SAMPLING_RATE_BITRATE_LIMITS_MP3`) — delete that `val` entirely — and update lines 81-85:

```kotlin
val SAMPLING_RATE_BITRATE_LIMITS = mapOf(
    EXTENSION_M4A to SAMPLING_RATE_BITRATE_LIMITS_M4A,
    EXTENSION_OGG to SAMPLING_RATE_BITRATE_LIMITS_OPUS
)
```

- [x] **Step 4: Simplify `Config.getExtension()`/`getExtensionText()` in `Config.kt`**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt`, replace lines 53-67:

```kotlin
    fun getExtension() = context.getString(
        when (extension) {
            EXTENSION_OGG -> R.string.ogg
            else -> R.string.m4a
        }
    )
```

(`getExtensionText()` is deleted here too — it was only consumed by the extension-picker Settings row, which Task 9 removes; deleting it now avoids a dead method sitting around between tasks.)

Note: `SettingsActivity.setupExtension()` still references `getExtensionText()` and `EXTENSION_MP3`/`R.string.mp3_experimental`/`R.string.ogg_opus` at this point, and that row isn't removed until Task 9. To keep the build green between tasks, its RadioItem list dropped the mp3 option and both text assignments were switched to `config.getExtension()`, with the ogg RadioItem now using `R.string.ogg` instead of the deleted `ogg_opus` string. This picker row (and the rest of its wiring) is still slated for full removal in Task 9.

- [x] **Step 5: Drop the AndroidLame license flag in `MainActivity`**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt`, in `launchAbout()`:

```kotlin
        val licenses = LICENSE_EVENT_BUS or
                LICENSE_AUDIO_RECORD_VIEW or
                LICENSE_AUTOFITTEXTVIEW
```

Remove the now-unused `import org.fossify.commons.helpers.LICENSE_ANDROID_LAME`.

- [x] **Step 6: Remove mp3 strings from `donottranslate.xml`**

```xml
<resources>
    <string name="package_name">ai.lequipe.lr</string>
    <string name="app_name">LR</string>
    <string name="m4a">m4a</string>
    <string name="ogg">ogg</string>
</resources>
```

(`mp3`, `mp3_experimental`, `ogg_opus` are gone. `bitrate_value`/`sampling_rate_value` were kept, not removed as this step originally described — `SettingsActivity.getBitrateText()`/`getSamplingRateText()` still reference them until Task 9 removes the bitrate/sampling-rate pickers entirely, so deleting them now would break the build.)

- [x] **Step 7: Remove the `tandroidlame` dependency**

Edit `gradle/libs.versions.toml`, delete lines 20-21 (the `#TAndroidLame` / `tandroidlame = "1.1"` version entry) and lines 45-46 (the `tandroidlame` library entry).

Edit `app/build.gradle.kts` line 147, delete `implementation(libs.tandroidlame)`.

- [x] **Step 8: Build and verify**

Run: `./gradlew assembleDebug detekt`
Expected: both succeed with no references to `Mp3Recorder`, `EXTENSION_MP3`, or `tandroidlame` remaining.

Run: `grep -rn "Mp3Recorder\|EXTENSION_MP3\|tandroidlame\|LICENSE_ANDROID_LAME" app/src gradle app/build.gradle.kts`
Expected: no matches.

Note: both passed clean (JDK 17 via `/opt/homebrew/opt/openjdk@17`). The grep for
`Mp3Recorder|EXTENSION_MP3|tandroidlame|LICENSE_ANDROID_LAME` returned no matches.

- [x] **Step 9: Commit**

```bash
git add app/src/main/kotlin/org/fossify/voicerecorder/services/RecorderService.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/activities/SettingsActivity.kt \
  app/src/main/res/values/donottranslate.xml gradle/libs.versions.toml app/build.gradle.kts
git rm app/src/main/kotlin/org/fossify/voicerecorder/recorder/Mp3Recorder.kt
git commit -m "feat: drop mp3/AndroidLame recording path, keep m4a and ogg only"
```

---

### Task 8: Fix the filename pattern; remove the pattern editor dialogs

**Files:**
- Create: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Filenames.kt`
- Create: `app/src/test/kotlin/org/fossify/voicerecorder/helpers/FilenamesTest.kt`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/extensions/Context.kt:242-261`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt:100-102`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt:105,108`
- Delete: `app/src/main/kotlin/org/fossify/voicerecorder/dialogs/FilenamePatternDialog.kt`
- Delete: `app/src/main/kotlin/org/fossify/voicerecorder/dialogs/DateTimePatternInfoDialog.kt`
- Delete: `app/src/main/res/layout/dialog_filename_pattern.xml`
- Delete: `app/src/main/res/layout/datetime_pattern_info_layout.xml`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: `generateRecordingFilename(now: Calendar = Calendar.getInstance()): String` in `helpers/Filenames.kt`, returning e.g. `"20260729_140000"` — consumed by `Context.getFormattedFilename()`.

- [ ] **Step 1: Add a JUnit dependency (first `src/test` in this repo)**

Edit `gradle/libs.versions.toml`, add to `[versions]`:

```toml
junit = "4.13.2"
```

and to `[libraries]`:

```toml
junit = { module = "junit:junit", version.ref = "junit" }
```

Edit `app/build.gradle.kts`, in the `dependencies` block:

```kotlin
    testImplementation(libs.junit)
```

- [ ] **Step 2: Write the failing test for the fixed filename format**

Create `app/src/test/kotlin/org/fossify/voicerecorder/helpers/FilenamesTest.kt`:

```kotlin
package org.fossify.voicerecorder.helpers

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FilenamesTest {
    private fun calendarFor(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): Calendar {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.set(year, month - 1, day, hour, minute, second)
        return calendar
    }

    @Test
    fun `formats as yyyyMMdd underscore HHmmss`() {
        val calendar = calendarFor(2026, 7, 29, 14, 5, 9)
        assertEquals("20260729_140509", generateRecordingFilename(calendar))
    }

    @Test
    fun `zero-pads single-digit month, day, hour, minute, and second`() {
        val calendar = calendarFor(2026, 1, 2, 3, 4, 5)
        assertEquals("20260102_030405", generateRecordingFilename(calendar))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "org.fossify.voicerecorder.helpers.FilenamesTest"`
Expected: FAIL — `generateRecordingFilename` is unresolved (doesn't exist yet).

- [ ] **Step 4: Implement `generateRecordingFilename`**

Create `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Filenames.kt`:

```kotlin
package org.fossify.voicerecorder.helpers

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val RECORDING_FILENAME_PATTERN = "yyyyMMdd_HHmmss"

fun generateRecordingFilename(now: Calendar = Calendar.getInstance()): String {
    val formatter = SimpleDateFormat(RECORDING_FILENAME_PATTERN, Locale.ROOT)
    return formatter.format(now.time)
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "org.fossify.voicerecorder.helpers.FilenamesTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Wire `Context.getFormattedFilename()` to the new pure function**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/extensions/Context.kt`, replace `getFormattedFilename()` (lines 242-261):

```kotlin
fun Context.getFormattedFilename(): String = generateRecordingFilename()
```

Remove the now-unused imports `java.util.Calendar` and `java.util.Locale` if nothing else in the file uses them (check with a grep first), and add `import org.fossify.voicerecorder.helpers.generateRecordingFilename`.

- [ ] **Step 7: Remove the `filenamePattern` config property and its constants**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt`, delete the `filenamePattern` property (lines 100-102).

Edit `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt`, delete line 105 (`const val FILENAME_PATTERN = "filename_pattern"`) and line 108 (`const val DEFAULT_FILENAME_PATTERN = "%Y%M%D_%h%m%s"`).

- [ ] **Step 8: Delete the pattern editor dialogs and their layouts**

```bash
git rm app/src/main/kotlin/org/fossify/voicerecorder/dialogs/FilenamePatternDialog.kt
git rm app/src/main/kotlin/org/fossify/voicerecorder/dialogs/DateTimePatternInfoDialog.kt
git rm app/src/main/res/layout/dialog_filename_pattern.xml
git rm app/src/main/res/layout/datetime_pattern_info_layout.xml
```

Note: `SettingsActivity.setupFilenamePattern()` still references `FilenamePatternDialog` at this point — that call site is removed in Task 9 along with the rest of the settings row. Leaving it dangling between these two tasks would break the build, so pull `setupFilenamePattern()`'s call and method body out now too:

Edit `app/src/main/kotlin/org/fossify/voicerecorder/activities/SettingsActivity.kt`: remove the `setupFilenamePattern()` call from `onResume()` and delete the method body, and remove the `import org.fossify.voicerecorder.dialogs.FilenamePatternDialog` import. (The layout row itself — `settings_filename_pattern_holder` — is removed from `activity_settings.xml` together with the rest of the recording-settings section in Task 9, to keep that layout diff in one place.)

- [ ] **Step 9: Build and verify**

Run: `./gradlew assembleDebug detekt testDebugUnitTest`
Expected: all succeed.

- [ ] **Step 10: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
  app/src/test/kotlin/org/fossify/voicerecorder/helpers/FilenamesTest.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/helpers/Filenames.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/extensions/Context.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/activities/SettingsActivity.kt
git rm app/src/main/kotlin/org/fossify/voicerecorder/dialogs/FilenamePatternDialog.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/dialogs/DateTimePatternInfoDialog.kt \
  app/src/main/res/layout/dialog_filename_pattern.xml \
  app/src/main/res/layout/datetime_pattern_info_layout.xml
git commit -m "feat: fix recording filenames to yyyyMMdd_HHmmss, remove pattern editor"
```

---

### Task 9: Trim the Settings screen

**Files:**
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/activities/SettingsActivity.kt`
- Modify: `app/src/main/res/layout/activity_settings.xml`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt:25,29-38,48-51,94-98`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt:97-98,100,104`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt:79-86`

**Interfaces:**
- Consumes: `RecorderService`/`MediaRecorderWrapper` still read `config.microphoneMode`/`config.bitrate`/`config.samplingRate`/`config.extension` directly (unchanged) — this task removes only the **UI** for changing them; the properties stay at their existing defaults (`EXTENSION_M4A`, `DEFAULT_BITRATE`, `DEFAULT_SAMPLING_RATE`, `MediaRecorder.AudioSource.DEFAULT`) until a later plan (endpoint enrollment) starts writing server-provided values into them.
- Produces: Settings screen left with only: save-recordings-folder, keep-screen-on, use-english/language/date-time-format (untouched, out of scope), About link.

- [ ] **Step 1: Remove the microphone-mode picker**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/activities/SettingsActivity.kt`: remove `setupMicrophoneMode()` from `onResume()`, delete `setupMicrophoneMode()` and `showMicrophoneModeDialog()` and `getMediaRecorderAudioSources()` methods, remove the `import android.media.MediaRecorder` if nothing else uses it.

Edit `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt`: delete `getMicrophoneModeText()` (lines 29-38) and `wasMicModeWarningShown` (lines 94-98). Keep the `microphoneMode` property itself (line 25) — `MediaRecorderWrapper` still reads it.

Edit `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt`: delete line 104 (`const val WAS_MIC_MODE_WARNING_SHOWN = "was_mic_mode_warning_shown"`). Keep line 97 (`MICROPHONE_MODE`).

- [ ] **Step 2: Remove the extension/bitrate/sample-rate pickers**

Edit `SettingsActivity.kt`: remove `setupExtension()`, `setupBitrate()`, `getBitrateText()`, `adjustBitrate()`, `setupSamplingRate()`, `getSamplingRateText()`, `getSamplingRatesArray()`, `adjustSamplingRate()` and their call sites in `onResume()`. Remove the now-unused imports (`RadioGroupDialog`, `RadioItem`, `BITRATES`, `DEFAULT_BITRATE`, `DEFAULT_SAMPLING_RATE`, `EXTENSION_M4A`, `EXTENSION_MP3`, `EXTENSION_OGG`, `SAMPLING_RATES`, `SAMPLING_RATE_BITRATE_LIMITS`, `kotlin.math.abs`, `isQPlus` if unused elsewhere in the file — check with a grep first).

Keep `Config.extension`/`bitrate`/`samplingRate` properties and `Config.getExtension()`/`getOutputFormat()`/`getAudioEncoder()` untouched — `RecorderService` still needs them.

- [ ] **Step 3: Remove "record after launch"**

Edit `SettingsActivity.kt`: remove `setupRecordAfterLaunch()` and its call site.

Edit `Config.kt`: delete the `recordAfterLaunch` property (lines 48-51).

Edit `Constants.kt`: delete line 100 (`const val RECORD_AFTER_LAUNCH = "record_after_launch"`).

Edit `app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt`, remove the dead auto-start block in `onCreate()` (lines 79-86):

```kotlin
        bus = EventBus.getDefault()
        bus!!.register(this)
    }
```

(replacing the `if (config.recordAfterLaunch && !RecorderService.isRunning) { ... }` block — nothing can set this to `true` anymore once the toggle is gone, so the block is dead code, and this matches the design spec's final Settings screen not listing "record after launch" as something that survives).

- [ ] **Step 4: Remove the recycle-bin toggle and the empty-recycle-bin row from Settings**

Edit `SettingsActivity.kt`: remove `setupUseRecycleBin()`, `updateRecycleBinButtons()`, `setupEmptyRecycleBin()` and their call sites, `recycleBinContentSize` field, and the `settingsRecycleBinLabel` entry from the color-tinting array. Remove now-unused imports (`ConfirmationDialog` may still be used elsewhere in the file — check before removing; `deleteTrashedRecordings`, `getAllRecordings`, `hasRecordings`/`formatSize`/`sumByInt` if unused, `Events`, `EventBus`).

(Recycle-bin-toggle removal from `Config`/`Constants` and the Recording adapters happens in Task 10, since it touches `RecordingsAdapter`/`DeleteConfirmationDialog` too — this step only removes the Settings-screen surface.)

- [ ] **Step 4b: Confirm the final `onResume()` after all of Steps 1-4 (plus Task 3/4's earlier removals)**

After every `setupX()` removed across Tasks 3, 4, and this task's Steps 1-4, `SettingsActivity.onResume()` should read exactly:

```kotlin
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
```

(The `arrayOf(...).forEach { it.setTextColor(...) }` block collapses to a single line since `settingsColorCustomizationSectionLabel`, `settingsRecordingSectionLabel`, `settingsAudioSectionLabel`, and `settingsRecycleBinLabel` are all gone from the layout by this point — only `settingsGeneralSettingsLabel` remains.)

- [ ] **Step 5: Trim `activity_settings.xml` to match**

Edit `app/src/main/res/layout/activity_settings.xml`: delete the entire "Recording" section (`settings_recording_section_label` through `settings_recording_divider`, i.e. filename-pattern and extension rows), the entire "Audio" section (`settings_audio_section_label` through `settings_audio_divider`, i.e. bitrate/sample-rate/microphone-mode rows), the `settings_record_after_launch_holder` row, and the entire "Recycle bin" section (`settings_recycle_bin_label` through `settings_empty_recycle_bin_holder`). Remove `settings_recording_section_label`, `settings_audio_section_label`, `settings_recycle_bin_label` from `SettingsActivity`'s color-tinting array (already partly done in earlier steps — confirm all three are gone).

What remains in the file: the "General settings" section (use-english, language, change-date-time-format — untouched) and the `settings_keep_screen_on_holder` / `settings_save_recordings_holder` rows.

- [ ] **Step 6: Build and verify**

Run: `./gradlew assembleDebug detekt`
Expected: both succeed.

Run: `grep -rn "microphoneMode\b\|EXTENSION_MP3\|filenamePattern\|recordAfterLaunch\|useRecycleBin" app/src/main/kotlin/org/fossify/voicerecorder/activities/SettingsActivity.kt`
Expected: no matches (all UI for these is gone; `useRecycleBin` reads elsewhere are handled in Task 10).

Manually open Settings and confirm only "Save recordings in", "Keep screen on", language/date-time rows, and the toolbar's back arrow remain.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/org/fossify/voicerecorder/activities/SettingsActivity.kt \
  app/src/main/res/layout/activity_settings.xml \
  app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt
git commit -m "chore: trim Settings to save-folder, keep-screen-on, and general settings"
```

---

### Task 10: Make the recycle bin always-on

**Files:**
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt:81-83`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt:101`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/adapters/RecordingsAdapter.kt:144-174`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/dialogs/DeleteConfirmationDialog.kt`
- Modify: `app/src/main/res/layout/dialog_delete_confirmation.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/fragments/PlayerFragment.kt:63,77,382`

**Interfaces:**
- Produces: `DeleteConfirmationDialog(activity, message, callback: () -> Unit)` — drops the `showSkipRecycleBinOption`/`skipRecycleBin` parameter entirely; every delete from the main list is now unconditionally a trash operation.

- [ ] **Step 1: Remove `Config.useRecycleBin`**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt`, delete lines 81-83 (the `useRecycleBin` property).

Edit `app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt`, delete line 101 (`const val USE_RECYCLE_BIN = "use_recycle_bin"`).

- [ ] **Step 2: Simplify `DeleteConfirmationDialog` to a plain trash confirmation**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/dialogs/DeleteConfirmationDialog.kt`:

```kotlin
package org.fossify.voicerecorder.dialogs

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.voicerecorder.databinding.DialogDeleteConfirmationBinding

class DeleteConfirmationDialog(
    private val activity: Activity,
    private val message: String,
    private val callback: () -> Unit
) {

    private var dialog: AlertDialog? = null
    val binding = DialogDeleteConfirmationBinding.inflate(activity.layoutInflater)
    val view = binding.root

    init {
        binding.deleteRememberTitle.text = message
        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.yes) { _, _ -> dialogConfirmed() }
            .setNegativeButton(org.fossify.commons.R.string.no, null)
            .apply {
                activity.setupDialogStuff(view, this) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    private fun dialogConfirmed() {
        dialog?.dismiss()
        callback()
    }
}
```

Edit `app/src/main/res/layout/dialog_delete_confirmation.xml`, remove the `skip_the_recycle_bin_checkbox` view:

```xml
<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/delete_remember_holder"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:paddingLeft="@dimen/big_margin"
    android:paddingTop="@dimen/big_margin"
    android:paddingRight="@dimen/big_margin">

    <org.fossify.commons.views.MyTextView
        android:id="@+id/delete_remember_title"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:paddingStart="@dimen/small_margin"
        android:paddingEnd="@dimen/small_margin"
        android:paddingBottom="@dimen/activity_margin"
        android:text="@string/delete_recordings_confirmation"
        android:textSize="@dimen/bigger_text_size" />

</RelativeLayout>
```

Remove the now-unused `skip_the_recycle_bin` string from `app/src/main/res/values/strings.xml` (grep first to confirm no other usage).

- [ ] **Step 3: Simplify `RecordingsAdapter`'s delete flow to always trash**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/adapters/RecordingsAdapter.kt`, replace `askConfirmDelete()` and `deleteRecordings()` (lines 144-174 through the start of `deleteRecordings()`):

```kotlin
    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size
        val firstItem = getSelectedItems().firstOrNull() ?: return
        val items = if (itemsCnt == 1) {
            "\"${firstItem.title}\""
        } else {
            resources.getQuantityString(R.plurals.delete_recordings, itemsCnt, itemsCnt)
        }

        val question = String.format(
            resources.getString(org.fossify.commons.R.string.move_to_recycle_bin_confirmation),
            items
        )

        DeleteConfirmationDialog(activity = activity, message = question) {
            ensureBackgroundThread {
                trashRecordings()
            }
        }
    }
```

Delete the standalone `deleteRecordings()` method (the permanent-delete path) from this adapter entirely — `TrashAdapter` still has its own `deleteMediaStoreRecordings()`/`askConfirmDelete()` for permanent purge from the bin, which is untouched.

Remove the now-unused `import org.fossify.voicerecorder.extensions.deleteRecordings` from this file (`trashRecordings` import stays).

- [ ] **Step 4: Remove `PlayerFragment`'s `useRecycleBin` tracking**

(This fragment is renamed/merged in Task 11, but fix the dangling `config.useRecycleBin` reference now so the build stays green between tasks.)

Edit `app/src/main/kotlin/org/fossify/voicerecorder/fragments/PlayerFragment.kt`: remove `prevRecycleBinState` (line 63) and its use in `onResume()` (line 77) and `storePrevState()` (line 382):

```kotlin
    override fun onResume() {
        setupColors()
        if (prevSavePath.isNotEmpty() && context!!.config.saveRecordingsFolder != prevSavePath) {
            loadRecordings()
        } else {
            getRecordingsAdapter()?.updateTextColor(context.getProperTextColor())
        }

        storePrevState()
    }
```

```kotlin
    private fun storePrevState() {
        prevSavePath = context!!.config.saveRecordingsFolder
    }
```

- [ ] **Step 5: Build and verify**

Run: `./gradlew assembleDebug detekt`
Expected: both succeed.

Manually: long-press a recording in the list, tap delete — confirm it always asks "move to recycle bin?" with no checkbox, and the item lands in the Recycle Bin tab.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/org/fossify/voicerecorder/helpers/Config.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/helpers/Constants.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/adapters/RecordingsAdapter.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/dialogs/DeleteConfirmationDialog.kt \
  app/src/main/res/layout/dialog_delete_confirmation.xml \
  app/src/main/res/values/strings.xml \
  app/src/main/kotlin/org/fossify/voicerecorder/fragments/PlayerFragment.kt
git commit -m "feat: make the recycle bin always-on, simplify delete to a single trash flow"
```

---

### Task 11: Merge Recorder + Player into one Recordings screen

**Files:**
- Create: `app/src/main/kotlin/org/fossify/voicerecorder/fragments/RecordingsFragment.kt`
- Create: `app/src/main/res/layout/fragment_recordings.xml`
- Delete: `app/src/main/kotlin/org/fossify/voicerecorder/fragments/RecorderFragment.kt`
- Delete: `app/src/main/kotlin/org/fossify/voicerecorder/fragments/PlayerFragment.kt`
- Delete: `app/src/main/res/layout/fragment_recorder.xml`
- Delete: `app/src/main/res/layout/fragment_player.xml`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/adapters/ViewPagerAdapter.kt`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt:190-252`
- Modify: `app/src/main/res/layout/item_recording.xml`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/adapters/RecordingsAdapter.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `ViewPagerAdapter(activity: SimpleActivity)` (drops the `showRecycleBin` constructor param), `getCount()` always `2`; position `0` → `RecordingsFragment`, position `1` → `TrashFragment`.
- Produces: `RecordingsAdapter.updateCurrentRecording(newId: Int, isPlaying: Boolean)` (adds the `isPlaying` param, was `updateCurrentRecording(newId: Int)`).

- [ ] **Step 1: Add a play/pause indicator to the recording row layout**

Edit `app/src/main/res/layout/item_recording.xml`, add an `ImageView` before `recording_title`, and shift `recording_title`'s start constraint to it:

```xml
        <ImageView
            android:id="@+id/recording_play_pause"
            android:layout_width="@dimen/normal_icon_size"
            android:layout_height="@dimen/normal_icon_size"
            android:layout_marginEnd="@dimen/normal_margin"
            android:contentDescription="@string/playpause"
            android:src="@drawable/ic_play_vector"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <TextView
            android:id="@+id/recording_title"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:ellipsize="end"
            android:includeFontPadding="false"
            android:maxLines="2"
            android:paddingEnd="@dimen/activity_margin"
            android:textSize="@dimen/bigger_text_size"
            app:layout_constraintBottom_toTopOf="@+id/recording_date"
            app:layout_constraintEnd_toStartOf="@+id/recording_duration"
            app:layout_constraintStart_toEndOf="@+id/recording_play_pause"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintVertical_bias="0.5"
            app:layout_constraintVertical_chainStyle="packed"
            tools:text="2020_03_30_22_49_52" />
```

- [ ] **Step 2: Extend `RecordingsAdapter` to show per-row play/pause state**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/adapters/RecordingsAdapter.kt`. Add an `isCurrentlyPlaying` field and change `updateCurrentRecording`:

```kotlin
    var currRecordingId = 0
    private var isCurrentlyPlaying = false
```

```kotlin
    fun updateCurrentRecording(newId: Int, isPlaying: Boolean) {
        val oldId = currRecordingId
        currRecordingId = newId
        isCurrentlyPlaying = isPlaying
        notifyItemChanged(recordings.indexOfFirst { it.id == oldId })
        notifyItemChanged(recordings.indexOfFirst { it.id == newId })
    }
```

Update `setupView()` to set the icon:

```kotlin
    private fun setupView(view: View, recording: Recording) {
        ItemRecordingBinding.bind(view).apply {
            root.setupViewBackground(activity)
            recordingFrame.isSelected = selectedKeys.contains(recording.id)

            arrayListOf(
                recordingTitle,
                recordingDate,
                recordingDuration,
                recordingSize
            ).forEach {
                it.setTextColor(textColor)
            }

            if (recording.id == currRecordingId) {
                recordingTitle.setTextColor(root.context.getProperPrimaryColor())
            }

            val isThisRowPlaying = recording.id == currRecordingId && isCurrentlyPlaying
            recordingPlayPause.setImageResource(
                if (isThisRowPlaying) {
                    org.fossify.commons.R.drawable.ic_pause_vector
                } else {
                    org.fossify.commons.R.drawable.ic_play_vector
                }
            )

            recordingTitle.text = recording.title
            recordingDate.text = recording.timestamp.formatDate(root.context)
            recordingDuration.text = recording.duration.getFormattedDuration()
            recordingSize.text = recording.size.formatSize()
        }
    }
```

- [ ] **Step 3: Create the merged layout**

Create `app/src/main/res/layout/fragment_recordings.xml`, combining the recorder controls from `fragment_recorder.xml` (top) with the list from `fragment_player.xml` (below), dropping the entire `player_controls_wrapper` transport bar:

```xml
<?xml version="1.0" encoding="utf-8"?>
<org.fossify.voicerecorder.fragments.RecordingsFragment xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/recordings_holder"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.visualizer.amplitude.AudioRecordView
        android:id="@+id/recorder_visualizer"
        android:layout_width="match_parent"
        android:layout_height="140dp"
        android:layout_margin="@dimen/big_margin"
        android:background="@drawable/frame_background"
        app:chunkAlignTo="center"
        app:chunkMaxHeight="120dp"
        app:chunkMinHeight="2dp"
        app:chunkRoundedCorners="true"
        app:chunkSoftTransition="true"
        app:chunkSpace="1dp"
        app:chunkWidth="3dp"
        app:layout_constraintTop_toTopOf="parent" />

    <org.fossify.commons.views.MyTextView
        android:id="@+id/recording_duration"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="@dimen/normal_margin"
        android:textSize="@dimen/extra_big_text_size"
        app:layout_constraintBottom_toTopOf="@+id/toggle_recording_button"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/recorder_visualizer"
        tools:text="00:00" />

    <androidx.appcompat.widget.AppCompatImageView
        android:id="@+id/cancel_recording_button"
        android:layout_width="@dimen/fab_size"
        android:layout_height="@dimen/fab_size"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:clickable="true"
        android:contentDescription="@string/cancel"
        android:focusable="true"
        android:padding="@dimen/normal_margin"
        android:src="@drawable/ic_cancel_recording_vector"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="@id/toggle_recording_button"
        app:layout_constraintEnd_toStartOf="@id/toggle_recording_button"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="@id/toggle_recording_button"
        tools:visibility="visible" />

    <androidx.appcompat.widget.AppCompatImageView
        android:id="@+id/toggle_recording_button"
        android:layout_width="@dimen/toggle_recording_button_size"
        android:layout_height="@dimen/toggle_recording_button_size"
        android:layout_marginBottom="@dimen/normal_margin"
        android:background="@drawable/circle_button_background"
        android:clickable="true"
        android:focusable="true"
        android:padding="@dimen/normal_margin"
        android:src="@drawable/ic_start_recording_vector"
        app:layout_constraintBottom_toTopOf="@+id/recordings_fastscroller"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <androidx.appcompat.widget.AppCompatImageView
        android:id="@+id/save_recording_button"
        android:layout_width="@dimen/fab_size"
        android:layout_height="@dimen/fab_size"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:clickable="true"
        android:focusable="true"
        android:padding="@dimen/normal_margin"
        android:src="@drawable/ic_save_recording_vector"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="@id/toggle_recording_button"
        app:layout_constraintStart_toEndOf="@id/toggle_recording_button"
        app:layout_constraintTop_toTopOf="@id/toggle_recording_button"
        tools:visibility="visible" />

    <org.fossify.commons.views.MyTextView
        android:id="@+id/recordings_placeholder"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:alpha="0.8"
        android:gravity="center"
        android:lineSpacingExtra="@dimen/small_margin"
        android:padding="@dimen/activity_margin"
        android:text="@string/no_recordings_found"
        android:textSize="@dimen/bigger_text_size"
        android:textStyle="italic"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/toggle_recording_button" />

    <com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
        android:id="@+id/recordings_fastscroller"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/toggle_recording_button">

        <org.fossify.commons.views.MyRecyclerView
            android:id="@+id/recordings_list"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:clipToPadding="false"
            android:layoutAnimation="@anim/layout_animation"
            android:scrollbars="none"
            app:layoutManager="org.fossify.commons.views.MyLinearLayoutManager" />

    </com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller>

    <com.google.android.material.progressindicator.CircularProgressIndicator
        android:id="@+id/loading_indicator"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:indeterminate="true"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/toggle_recording_button" />

</org.fossify.voicerecorder.fragments.RecordingsFragment>
```

- [ ] **Step 4: Create the merged fragment**

Create `app/src/main/kotlin/org/fossify/voicerecorder/fragments/RecordingsFragment.kt` by combining `RecorderFragment` (recorder controls/EventBus subscriptions) with `PlayerFragment`'s list/`MediaPlayer` logic, minus the transport bar (seek bar, prev/next, title, becoming-noisy receiver — dropped along with scrubbing/skip, since a single row's inline play/pause doesn't need "next"/"previous" or a `BecomingNoisyReceiver` pause-on-unplug convenience beyond what pausing on tap already gives):

```kotlin
package org.fossify.voicerecorder.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.AttributeSet
import androidx.core.net.toUri
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.compose.extensions.getActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.commons.extensions.setDebouncedClickListener
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.isQPlus
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.adapters.RecordingsAdapter
import org.fossify.voicerecorder.databinding.FragmentRecordingsBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.ensureStoragePermission
import org.fossify.voicerecorder.extensions.setKeepScreenAwake
import org.fossify.voicerecorder.helpers.CANCEL_RECORDING
import org.fossify.voicerecorder.helpers.GET_RECORDER_INFO
import org.fossify.voicerecorder.helpers.RECORDING_PAUSED
import org.fossify.voicerecorder.helpers.RECORDING_RUNNING
import org.fossify.voicerecorder.helpers.RECORDING_STOPPED
import org.fossify.voicerecorder.helpers.TOGGLE_PAUSE
import org.fossify.voicerecorder.interfaces.RefreshRecordingsListener
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.Recording
import org.fossify.voicerecorder.services.RecorderService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Timer
import java.util.TimerTask

class RecordingsFragment(
    context: Context,
    attributeSet: AttributeSet
) : MyViewPagerFragment(context, attributeSet), RefreshRecordingsListener {

    private var status = RECORDING_STOPPED
    private var pauseBlinkTimer = Timer()
    private var bus: EventBus? = null
    private var player: MediaPlayer? = null
    private var itemsIgnoringSearch = ArrayList<Recording>()
    private var lastSearchQuery = ""
    private var prevSavePath = ""
    private lateinit var binding: FragmentRecordingsBinding

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentRecordingsBinding.bind(this)
    }

    override fun onResume() {
        setupColors()
        if (!RecorderService.isRunning) {
            status = RECORDING_STOPPED
        }

        if (prevSavePath.isNotEmpty() && context!!.config.saveRecordingsFolder != prevSavePath) {
            loadRecordings()
        } else {
            getRecordingsAdapter()?.updateTextColor(context.getProperTextColor())
        }

        prevSavePath = context!!.config.saveRecordingsFolder
        refreshView()
    }

    override fun onDestroy() {
        bus?.unregister(this)
        pauseBlinkTimer.cancel()
        player?.stop()
        player?.release()
        player = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupColors()
        binding.recorderVisualizer.recreate()
        bus = EventBus.getDefault()
        bus!!.register(this)
        loadRecordings()
        initMediaPlayer()

        updateRecordingDuration(0)
        binding.toggleRecordingButton.setDebouncedClickListener {
            val activity = context as? BaseSimpleActivity
            activity?.ensureStoragePermission {
                if (it) {
                    activity.handleNotificationPermission { granted ->
                        if (granted) {
                            cycleRecordingState()
                        } else {
                            PermissionRequiredDialog(
                                activity = context as BaseSimpleActivity,
                                textId = org.fossify.commons.R.string.allow_notifications_voice_recorder,
                                positiveActionCallback = {
                                    (context as BaseSimpleActivity).openNotificationSettings()
                                }
                            )
                        }
                    }
                } else {
                    activity.toast(org.fossify.commons.R.string.no_storage_permissions)
                }
            }
        }

        binding.cancelRecordingButton.setDebouncedClickListener { showCancelRecordingDialog() }
        binding.saveRecordingButton.setDebouncedClickListener { saveRecording() }
        Intent(context, RecorderService::class.java).apply {
            action = GET_RECORDER_INFO
            try {
                context.startService(this)
            } catch (ignored: Exception) {
            }
        }
    }

    override fun refreshRecordings() = loadRecordings()

    override fun onLoadingStart() {
        if (itemsIgnoringSearch.isEmpty()) {
            binding.loadingIndicator.show()
        } else {
            binding.loadingIndicator.hide()
        }
    }

    override fun onLoadingEnd(recordings: ArrayList<Recording>) {
        binding.loadingIndicator.hide()
        binding.recordingsPlaceholder.beVisibleIf(recordings.isEmpty())
        itemsIgnoringSearch = recordings
        setupAdapter(itemsIgnoringSearch)
    }

    private fun setupAdapter(recordings: ArrayList<Recording>) {
        binding.recordingsFastscroller.beVisibleIf(recordings.isNotEmpty())
        if (recordings.isEmpty()) {
            val stringId = if (lastSearchQuery.isEmpty()) {
                if (isQPlus()) {
                    R.string.no_recordings_found
                } else {
                    R.string.no_recordings_in_folder_found
                }
            } else {
                org.fossify.commons.R.string.no_items_found
            }

            binding.recordingsPlaceholder.text = context.getString(stringId)
            player?.stop()
        }

        val adapter = getRecordingsAdapter()
        if (adapter == null) {
            RecordingsAdapter(context as SimpleActivity, recordings, this, binding.recordingsList) {
                playRecording(it as Recording, true)
            }.apply {
                binding.recordingsList.adapter = this
            }

            if (context.areSystemAnimationsEnabled) {
                binding.recordingsList.scheduleLayoutAnimation()
            }
        } else {
            adapter.updateItems(recordings)
        }
    }

    private fun initMediaPlayer() {
        player = MediaPlayer().apply {
            setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )

            setOnCompletionListener {
                getRecordingsAdapter()?.updateCurrentRecording(0, false)
            }

            setOnPreparedListener {
                start()
                getRecordingsAdapter()?.updateCurrentRecording(getRecordingsAdapter()!!.currRecordingId, true)
            }
        }
    }

    override fun playRecording(recording: Recording, playOnPrepared: Boolean) {
        val adapter = getRecordingsAdapter() ?: return
        val isTappingCurrentlyPlayingRow = adapter.currRecordingId == recording.id && player?.isPlaying == true
        if (isTappingCurrentlyPlayingRow) {
            player?.pause()
            adapter.updateCurrentRecording(recording.id, false)
            return
        }

        player!!.apply {
            reset()

            try {
                setDataSource(context, recording.path.toUri())
            } catch (e: Exception) {
                context?.showErrorToast(e)
                return
            }

            try {
                prepareAsync()
            } catch (e: Exception) {
                context.showErrorToast(e)
                return
            }
        }
    }

    fun onSearchTextChanged(text: String) {
        lastSearchQuery = text
        val filtered = itemsIgnoringSearch
            .filter { it.title.contains(text, true) }
            .toMutableList() as ArrayList<Recording>
        setupAdapter(filtered)
    }

    private fun getRecordingsAdapter() = binding.recordingsList.adapter as? RecordingsAdapter

    private fun setupColors() {
        val properTextColor = context.getProperTextColor()
        val properPrimaryColor = context.getProperPrimaryColor()
        binding.toggleRecordingButton.apply {
            setImageDrawable(getToggleButtonIcon())
            background.applyColorFilter(properPrimaryColor)
        }

        binding.cancelRecordingButton.applyColorFilter(properTextColor)
        binding.saveRecordingButton.applyColorFilter(properTextColor)
        binding.recorderVisualizer.chunkColor = properPrimaryColor
        binding.recordingDuration.setTextColor(properTextColor)
        binding.recordingsFastscroller.updateColors(properPrimaryColor)
        context.updateTextColors(binding.recordingsHolder)
        binding.loadingIndicator.setIndicatorColor(properPrimaryColor)
    }

    private fun updateRecordingDuration(duration: Int) {
        binding.recordingDuration.text = duration.getFormattedDuration()
    }

    private fun getToggleButtonIcon(): Drawable {
        val drawable = if (status == RECORDING_RUNNING || status == RECORDING_PAUSED) {
            R.drawable.ic_pause_recording_vector
        } else {
            R.drawable.ic_start_recording_vector
        }

        return resources.getColoredDrawableWithColor(
            drawableId = drawable,
            color = context.getProperPrimaryColor().getContrastColor()
        )
    }

    private fun cycleRecordingState() {
        when (status) {
            RECORDING_PAUSED,
            RECORDING_RUNNING -> {
                Intent(context, RecorderService::class.java).apply {
                    action = TOGGLE_PAUSE
                    context.startService(this)
                }
            }

            else -> {
                startRecording()
            }
        }

        status = if (status == RECORDING_RUNNING) RECORDING_PAUSED else RECORDING_RUNNING
        binding.toggleRecordingButton.setImageDrawable(getToggleButtonIcon())
    }

    private fun startRecording() {
        Intent(context, RecorderService::class.java).apply {
            context.startService(this)
        }
    }

    private fun showCancelRecordingDialog() {
        val activity = context as? BaseSimpleActivity ?: return
        ConfirmationDialog(
            activity = activity,
            message = activity.getString(R.string.discard_recording_confirmation),
            dialogTitle = activity.getString(R.string.discard_recording)
        ) {
            cancelRecording()
        }
    }

    private fun cancelRecording() {
        status = RECORDING_STOPPED
        Intent(context, RecorderService::class.java).apply {
            action = CANCEL_RECORDING
            context.startService(this)
        }
        refreshView()
    }

    private fun saveRecording() {
        status = RECORDING_STOPPED
        Intent(context, RecorderService::class.java).apply {
            context.stopService(this)
        }
        refreshView()
    }

    private fun getPauseBlinkTask() = object : TimerTask() {
        override fun run() {
            if (status == RECORDING_PAUSED) {
                Handler(Looper.getMainLooper()).post {
                    binding.toggleRecordingButton.alpha =
                        if (binding.toggleRecordingButton.alpha == 0f) 1f else 0f
                }
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun refreshView() {
        binding.toggleRecordingButton.setImageDrawable(getToggleButtonIcon())
        binding.saveRecordingButton.beVisibleIf(status != RECORDING_STOPPED)
        binding.cancelRecordingButton.beVisibleIf(status != RECORDING_STOPPED)
        pauseBlinkTimer.cancel()

        when (status) {
            RECORDING_PAUSED -> {
                pauseBlinkTimer = Timer()
                pauseBlinkTimer.scheduleAtFixedRate(getPauseBlinkTask(), 500, 500)
            }

            RECORDING_RUNNING -> {
                binding.toggleRecordingButton.alpha = 1f
                if (context.config.keepScreenOn) {
                    context.getActivity().setKeepScreenAwake(true)
                }
            }

            else -> {
                binding.toggleRecordingButton.alpha = 1f
                binding.recorderVisualizer.recreate()
                binding.recordingDuration.text = null
            }
        }
    }

    fun finishActMode() = getRecordingsAdapter()?.finishActMode()

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun gotDurationEvent(event: Events.RecordingDuration) {
        updateRecordingDuration(event.duration)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun gotStatusEvent(event: Events.RecordingStatus) {
        status = event.status
        refreshView()
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun gotAmplitudeEvent(event: Events.RecordingAmplitude) {
        val amplitude = event.amplitude
        if (status == RECORDING_RUNNING) {
            binding.recorderVisualizer.update(amplitude)
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingCompleted(@Suppress("UNUSED_PARAMETER") event: Events.RecordingCompleted) {
        refreshRecordings()
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingMovedToRecycleBin(@Suppress("UNUSED_PARAMETER") event: Events.RecordingTrashUpdated) {
        refreshRecordings()
    }
}
```

Delete the old fragments and layouts:

```bash
git rm app/src/main/kotlin/org/fossify/voicerecorder/fragments/RecorderFragment.kt
git rm app/src/main/kotlin/org/fossify/voicerecorder/fragments/PlayerFragment.kt
git rm app/src/main/res/layout/fragment_recorder.xml
git rm app/src/main/res/layout/fragment_player.xml
```

Delete `app/src/main/kotlin/org/fossify/voicerecorder/receivers/BecomingNoisyReceiver.kt` — its only consumer (`PlayerFragment`'s pause-on-unplug) is gone, and nothing else in the codebase references it (confirm with a grep before deleting).

- [ ] **Step 5: Fix `RecordingsAdapter`'s `refreshListener.playRecording` call for the removed play-next-on-delete behavior**

The old `doDeleteAnimation()` called `refreshListener.playRecording(newRecording, false)` when the currently-playing item was deleted, to auto-advance playback — that "auto-advance" concept doesn't fit a per-row toggle model. Edit `app/src/main/kotlin/org/fossify/voicerecorder/adapters/RecordingsAdapter.kt`'s `doDeleteAnimation()` and its only call site, `trashRecordings()`:

```kotlin
    private fun trashRecordings() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val recordingsToRemove = recordings
            .filter { selectedKeys.contains(it.id) } as ArrayList<Recording>

        val positions = getSelectedItemPositions()

        activity.trashRecordings(recordingsToRemove) { success ->
            if (success) {
                doDeleteAnimation(recordingsToRemove, positions)
                EventBus.getDefault().post(Events.RecordingTrashUpdated())
            }
        }
    }

    private fun doDeleteAnimation(
        recordingsToRemove: ArrayList<Recording>,
        positions: ArrayList<Int>
    ) {
        recordings.removeAll(recordingsToRemove.toSet())
        activity.runOnUiThread {
            if (recordings.isEmpty()) {
                refreshListener.refreshRecordings()
                finishActMode()
            } else {
                positions.sortDescending()
                removeSelectedItems(positions)
            }
        }
    }
```

(`oldRecordingIndex` is gone from both the computation in `trashRecordings()` and the `doDeleteAnimation()` signature — nothing else in the file calls `doDeleteAnimation()`, so this is the only call site to update.)

- [ ] **Step 6: Fix `ViewPagerAdapter` to a fixed 2-tab pager**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/adapters/ViewPagerAdapter.kt`:

```kotlin
package org.fossify.voicerecorder.adapters

import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.fragments.MyViewPagerFragment
import org.fossify.voicerecorder.fragments.RecordingsFragment
import org.fossify.voicerecorder.fragments.TrashFragment

class ViewPagerAdapter(
    private val activity: SimpleActivity
) : PagerAdapter() {

    private val fragments = SparseArray<MyViewPagerFragment>()

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val layout = when (position) {
            0 -> R.layout.fragment_recordings
            1 -> R.layout.fragment_trash
            else -> throw IllegalArgumentException("Invalid position. Count = $count, requested position = $position")
        }

        val view = activity.layoutInflater.inflate(layout, container, false)
        container.addView(view)

        fragments.put(position, view as MyViewPagerFragment)
        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
        container.removeView(item as View)
    }

    override fun getCount() = 2

    override fun isViewFromObject(view: View, item: Any) = view == item

    fun onResume() {
        for (i in 0 until fragments.size()) {
            fragments[i].onResume()
        }
    }

    fun onDestroy() {
        for (i in 0 until fragments.size()) {
            fragments[i].onDestroy()
        }
    }

    fun finishActMode() {
        (fragments[0] as? RecordingsFragment)?.finishActMode()
        (fragments[1] as? TrashFragment)?.finishActMode()
    }

    fun searchTextChanged(text: String) {
        (fragments[0] as? RecordingsFragment)?.onSearchTextChanged(text)
        (fragments[1] as? TrashFragment)?.onSearchTextChanged(text)
    }
}
```

- [ ] **Step 7: Fix `MainActivity` for the fixed 2-tab pager**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt`, replace `setupViewPager()` (lines 190-252):

```kotlin
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
                if (it.position == 1) {
                    binding.mainMenu.closeSearch()
                }
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
        }

        binding.viewPager.currentItem = config.lastUsedViewPagerPage
        binding.mainTabsHolder.getTabAt(config.lastUsedViewPagerPage)?.select()
    }
```

Replace `onResume()`'s pager-rebuild check — the pager is now always the same shape, so it only needs to be built once:

```kotlin
    override fun onResume() {
        super.onResume()
        updateMenuColors()
        if (binding.viewPager.adapter == null) {
            setupViewPager()
        }
        setupTabColors()
        getPagerAdapter()?.onResume()
    }
```

(`setupViewPager()` is still called the first time from `tryInitVoiceRecorder()` after permissions are granted, same as today — this change only removes the "was the toggle flipped" rebuild condition, since there's no toggle left. Confirm `tryInitVoiceRecorder()`'s existing call to `setupViewPager()` stays as-is.)

- [ ] **Step 8: Add the `recordings` tab-label string, remove the now-unused `recorder`/`player` strings**

Edit `app/src/main/res/values/strings.xml`, add:

```xml
    <string name="recordings">Recordings</string>
```

Remove `<string name="recorder">Recorder</string>` and `<string name="player">Player</string>` (grep first to confirm no other usage — the only consumer was the tab-label array just replaced).

- [ ] **Step 9: Build and manually verify**

Run: `./gradlew assembleDebug detekt`
Expected: both succeed.

Run: `grep -rn "PlayerFragment\|RecorderFragment\|fragment_recorder\|fragment_player\|BecomingNoisyReceiver" app/src`
Expected: no matches.

Manually: install and confirm exactly 2 tabs (Recordings, Recycle Bin); record something, confirm it appears in the list below the recorder controls with a play icon; tap the row to play, tap again to pause; long-press to trash it, confirm it shows up in the Recycle Bin tab.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/org/fossify/voicerecorder/fragments/RecordingsFragment.kt \
  app/src/main/res/layout/fragment_recordings.xml \
  app/src/main/kotlin/org/fossify/voicerecorder/adapters/ViewPagerAdapter.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt \
  app/src/main/res/layout/item_recording.xml \
  app/src/main/kotlin/org/fossify/voicerecorder/adapters/RecordingsAdapter.kt \
  app/src/main/res/values/strings.xml
git rm app/src/main/kotlin/org/fossify/voicerecorder/fragments/RecorderFragment.kt \
  app/src/main/kotlin/org/fossify/voicerecorder/fragments/PlayerFragment.kt \
  app/src/main/res/layout/fragment_recorder.xml \
  app/src/main/res/layout/fragment_player.xml \
  app/src/main/kotlin/org/fossify/voicerecorder/receivers/BecomingNoisyReceiver.kt
git commit -m "feat: merge recorder and player into a single Recordings screen"
```

---

### Task 12: Move "Empty recycle bin" into the Recycle Bin tab's toolbar menu

**Files:**
- Modify: `app/src/main/res/menu/menu.xml`
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt`

**Interfaces:**
- Consumes: `Context.deleteExpiredTrashedRecordings()`'s sibling extension `BaseSimpleActivity.deleteTrashedRecordings()` (unchanged, from `extensions/Activity.kt`).

- [ ] **Step 1: Add the menu item, hidden by default**

Edit `app/src/main/res/menu/menu.xml`, add before the closing `</menu>`:

```xml
    <item
        android:id="@+id/empty_recycle_bin"
        android:icon="@drawable/ic_delete_vector"
        android:title="@string/empty_recycle_bin"
        android:visible="false"
        app:showAsAction="never" />
```

- [ ] **Step 2: Show/hide it based on the active tab, and wire the click**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt`. Add a menu-visibility refresh, called both from the page-change listener and right after the pager is built:

```kotlin
    private fun updateOptionsMenuForCurrentTab() {
        val isTrashTabActive = binding.viewPager.currentItem == 1
        binding.mainMenu.requireToolbar().menu.findItem(R.id.empty_recycle_bin).isVisible = isTrashTabActive
    }
```

In `setupViewPager()`, extend the `onPageChangeListener` block:

```kotlin
        binding.viewPager.onPageChangeListener {
            binding.mainTabsHolder.getTabAt(it)?.select()
            (binding.viewPager.adapter as ViewPagerAdapter).finishActMode()
            updateOptionsMenuForCurrentTab()
        }
```

and call `updateOptionsMenuForCurrentTab()` once at the end of `setupViewPager()` (after `binding.viewPager.currentItem = config.lastUsedViewPagerPage`), so the icon is already correct if the app opens straight into the Recycle Bin tab.

Extend `setupOptionsMenu()`'s click handler:

```kotlin
        binding.mainMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                R.id.empty_recycle_bin -> confirmEmptyRecycleBin()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
```

Add the confirmation + action method:

```kotlin
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
```

Add `import org.fossify.voicerecorder.extensions.deleteTrashedRecordings` and `import org.fossify.voicerecorder.models.Events` (the latter was removed from this file in Task 5 — re-add it here since it's needed again for `Events.RecordingTrashUpdated`).

- [ ] **Step 3: Build and manually verify**

Run: `./gradlew assembleDebug detekt`
Expected: both succeed.

Manually: with at least one item in the Recycle Bin, switch to the Recordings tab — confirm the toolbar shows no "Empty recycle bin" action; switch to the Recycle Bin tab — confirm it appears, tapping it asks for confirmation, and confirming empties the bin.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/menu/menu.xml \
  app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt
git commit -m "feat: move Empty Recycle Bin action into the Recycle Bin tab's toolbar"
```

---

### Task 13: GPLv3/Fossify attribution in the About screen

**Files:**
- Modify: `app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt` (`launchAbout()`)
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- None (leaf UI change).

- [ ] **Step 1: Add the attribution string**

Edit `app/src/main/res/values/strings.xml`, add:

```xml
    <string name="based_on_fossify_voice_recorder">LR is based on Fossify Voice Recorder (GPLv3), modified 2026 — source: https://github.com/aevdokimenko/voice-recorder</string>
```

- [ ] **Step 2: Add it as an always-first FAQ entry in `launchAbout()`**

Edit `app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt`'s `launchAbout()`:

```kotlin
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
```

Remove the now-dead `if (!resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations))` block that used to add two more FAQ items — with the multi-flavor `bools.xml` overrides gone (Task 2), this always reads whatever `org.fossify:commons` itself defaults `hide_google_relations` to, which is no longer a meaningful signal for this single-purpose internal app; drop the conditional and the two Google-relations FAQ items entirely.

- [ ] **Step 3: Build and manually verify**

Run: `./gradlew assembleDebug detekt`
Expected: both succeed.

Manually: open the About screen (toolbar overflow → About) and confirm the first FAQ entry shows the Fossify/GPLv3 attribution with the fork URL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/fossify/voicerecorder/activities/MainActivity.kt \
  app/src/main/res/values/strings.xml
git commit -m "docs: add GPLv3/Fossify Voice Recorder attribution to the About screen"
```

---

### Task 14: Final smoke build and manual verification pass

**Files:** none (verification only).

- [ ] **Step 1: Full clean build of every check**

Run: `./gradlew clean build`
Expected: succeeds — compiles, runs `detekt` (0 issues beyond the existing baseline), runs `lint` (debug only, per `checkReleaseBuilds = false`), runs the new `FilenamesTest`, and assembles debug + release APKs.

- [ ] **Step 2: Install on a device/emulator and walk the full golden path**

Run: `./gradlew installDebug`

Manually verify, in order:
1. Launcher icon is "LR" with no themed-icon long-press options.
2. First launch asks for microphone permission, then storage/SAF folder permission, then lands on the Recordings tab (record button + empty list).
3. Tap record → visualizer animates, duration counts up; tap pause/resume; tap cancel → recording discarded with a confirmation dialog; record again and tap save → a new row appears named like `20260729_140512.m4a` (today's date/time).
4. Tap the new row → it plays with the row's icon switching to pause; tap again → pauses.
5. Long-press the row → rename it; confirm the new name sticks and the file plays fine afterward.
6. Long-press → delete → confirms "move to recycle bin?" with no checkbox → item disappears from Recordings and appears in the Recycle Bin tab.
7. In the Recycle Bin tab, the toolbar shows "Empty recycle bin"; in the Recordings tab it doesn't.
8. Restore the item from the Recycle Bin → it reappears in the Recordings tab.
9. Add the home-screen widget; tap it to start a recording (icon turns red, per Task 4), tap again to stop (icon turns white); confirm the recording shows up in the app.
10. Settings screen shows only: save-recordings-folder, keep-screen-on, and the untouched general (language/date-time) rows — no format/bitrate/sample-rate/mic-mode/filename-pattern/recycle-bin-toggle rows.
11. About screen shows the GPLv3/Fossify attribution FAQ entry first.

- [ ] **Step 3: Grep sweep for anything the task-by-task removals might have missed**

Run:

```bash
grep -rn "org.fossify.voicerecorder\"" app/src/main/res/values/donottranslate.xml gradle.properties
grep -rln "Mp3Recorder\|EXTENSION_MP3\|showRecycleBin\|useRecycleBin\|filenamePattern\|recordAfterLaunch\|wasMicModeWarningShown\|getMediaStoreTrashedRecordings\|isThirdPartyIntent\|WidgetRecordDisplayConfigureActivity" app/src
```

Expected: first command has no output (package id fully rebranded); second command has no output (nothing left referencing the removed subsystems).

- [ ] **Step 4: Confirm this plan's scope boundary**

No commit needed for this step — it's a checklist confirmation, not a code change. Confirm that endpoint enrollment (QR scan, well-known check, `/enroll`/`/config`/`/presign`), the upload sidecar-status model, and the upload worker/retry logic are **not** present anywhere in this codebase yet — they're intentionally out of scope, covered by the next two plans (enrollment, then upload/retention) built on top of this one.
