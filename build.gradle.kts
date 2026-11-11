// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(frameworkLibs.plugins.android.library) apply false
    // Not applied (AGP 9's built-in Kotlin replaces it) -- declared only to pin the Kotlin Gradle
    // plugin, and so the Kotlin compiler, to the catalog's `kotlin` version. Without it the compiler
    // falls back to the older version AGP depends on unless another Kotlin plugin raises it.
    alias(frameworkLibs.plugins.kotlin.android) apply false
    alias(frameworkLibs.plugins.kotlin.serialization) apply false
    alias(frameworkLibs.plugins.maven.publish) apply false
}
