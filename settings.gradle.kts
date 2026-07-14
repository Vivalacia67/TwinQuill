pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TwinQuill"

include(
    ":launcher-app",
    ":engine-api",
    ":native-vfs",
    ":engine-ons",
    ":engine-krkr",
)
