# Local patches to Fossify Commons

Vendored from https://github.com/FossifyOrg/Commons at tag **6.1.6**
(commit `cbee1f1385a006f9f82c1a2c8b3722b5f0a242bd`).

Keep this list current — it is the whole reason this module is vendored rather than
consumed as the published `org.fossify:commons` artifact.

## 1. Remove the "fake version" nag (`compose/theme/AppTheme.kt`)

Upstream `AppTheme` calls `OnContentDisplayed()` -> `FakeVersionCheck()`, which shows
"You are using a fake version of the app. For your own safety download the original one
from www.fossify.org." whenever `packageName` does not start with `org.fossify.`, firing
randomly (`(0..50).random() == 10`, plus every 100th app run).

LR is a legitimate GPLv3 fork under its own `ai.lequipe.lr` namespace and credits Fossify
in its About screen, so the warning is both wrong and unavoidable otherwise: the call sits
inside `AppTheme`, which every commons Compose surface uses.

Patch: deleted the `OnContentDisplayed()` call, the function, and its import.
