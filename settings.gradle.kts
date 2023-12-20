import java.net.URI

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    // Set this as a different default than the standard "libs" so that other projects can easily
    // import it without version catalog naming conflicts.
    defaultLibrariesExtensionName = "frameworkLibs"
    repositories {
        google()
        mavenCentral()
        maven { url = URI("https://www.jitpack.io") }
    }
}
rootProject.name = "Combustion Inc. Android Framework"
include(":combustion-android-ble")
