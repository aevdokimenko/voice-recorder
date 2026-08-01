pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://www.jitpack.io") }
        mavenLocal()
    }
}
rootProject.name = "LR"
include(":app")

// Fossify Commons is vendored rather than consumed as the published org.fossify:commons
// artifact so it can be patched. See commons/LOCAL_PATCHES.md.
include(":commons")
