# Local patches to Fossify Commons

Vendored from https://github.com/FossifyOrg/Commons at tag **6.1.6**
(commit `cbee1f1385a006f9f82c1a2c8b3722b5f0a242bd`).

Keep this list current — it is the whole reason this module is vendored rather than consumed as
the published `org.fossify:commons` artifact.

---

## 1. Disable the "fake version" nag

Upstream warns *"You are using a fake version of the app. For your own safety download the
original one from www.fossify.org."* whenever `packageName` does not start with `org.fossify.`.
LR is a legitimate GPLv3 fork under its own `ai.lequipe.lr` namespace that credits Fossify in
its About screen, so the warning is simply wrong here.

There are **three independent implementations**, which is why this took several passes —
disabling one does not stop the others.

### 1a. `activities/BaseSimpleActivity.kt` — the one that actually fired

```kotlin
if (!packageName.startsWith("org.fossify.", true)) {
    if ((0..50).random() == 10 || baseConfig.appRunCount % 100 == 0) {
        showModdedAppWarning()
    }
}
```

This sits in `onCreate`, so it covers every activity in the app. Note that
`appRunCount % 100 == 0` makes it fire **on every launch of a fresh install** (`appRunCount`
starts at 0), and randomly ~2% of the time after that. Patch: block removed.

### 1b. `compose/extensions/ActivityExtensions.kt` — `Context.fakeVersionCheck`

The Compose equivalent, reached from `AppTheme` via `FakeVersionCheck()`. Patch: body replaced
with `Unit`. Neutering the function rather than its call sites is deliberate — Compose dispatches
it through synthetic lambdas, so removing the visible call was not sufficient.

### 1c. `compose/theme/AppTheme.kt`

Removed the `OnContentDisplayed()` helper and its `FakeVersionCheck()` call, so the dead path is
gone rather than merely inert.

> Still present but unreachable for LR: `showModdedAppWarning()` itself, and a second guard in
> `startCustomizationActivity()` (obfuscated as `"yfissof".reversed()`), which LR never calls
> because the theme-customization screen was removed.

---

## 2. Drop dangling `parentActivityName` (`src/main/AndroidManifest.xml`)

`LicenseActivity`, `FAQActivity` and `ContributorsActivity` declared
`parentActivityName="org.fossify.commons.activities.AboutActivity"`, which the app no longer
declares — LR ships its own minimal About screen.

---

## 3. Build wiring (not upstream behaviour)

- `maven-publish`, `group`/`version` and the `publishing` blocks were removed from
  `build.gradle.kts`; they are meaningless for a local module.
- `detekt` points at `commons/detekt.yml` (upstream's own config) rather than the app's stricter
  root config, so vendored third-party code is not gated on LR's style rules.
