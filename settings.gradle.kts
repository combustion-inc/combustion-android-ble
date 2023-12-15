import java.net.URI

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = URI("https://www.jitpack.io") }
    }
}
rootProject.name = "Combustion Inc. Android Framework"
include(":combustion-android-ble")
