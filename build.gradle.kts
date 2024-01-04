// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(frameworkLibs.plugins.android.library) apply false
    alias(frameworkLibs.plugins.kotlin.android) apply false
    alias(frameworkLibs.plugins.maven.publish) apply false
}
true // Needed to make the Suppress annotation work for the plugins block--this is boilerplate.