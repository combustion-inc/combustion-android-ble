// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    extra.apply {
        set("kotlin_version", "1.8.10")
        set("android_gradle_version", "8.2.0")
        set("appcompat_version", "1.5.1")
        set("lifecycle_runtime_ktx_version", "2.5.1")
        set("kable_core_version", "0.20.1")
        set("lifecycle_service_version", "2.5.1")
        set("junit_version", "1.1.4")
        set("espresso_core_version", "3.5.0")
    }
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.1.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.0")
        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
