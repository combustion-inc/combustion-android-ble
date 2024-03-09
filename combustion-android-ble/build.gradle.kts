import java.io.ByteArrayOutputStream
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

@Suppress("DSL_SCOPE_VIOLATION") // TODO: Remove once KTIJ-19369 is fixed--this is boilerplate.
plugins {
    alias(frameworkLibs.plugins.android.library)
    alias(frameworkLibs.plugins.kotlin.android)
    alias(frameworkLibs.plugins.maven.publish)
}

fun String.runCommand(currentWorkingDir: File = file("./")): String {
    val byteOut = ByteArrayOutputStream()

    project.exec {
        workingDir = currentWorkingDir
        commandLine = this@runCommand.split("\\s".toRegex())
        standardOutput = byteOut
        isIgnoreExitValue = true
    }

    return String(byteOut.toByteArray()).trim()
}

fun getVersionName(): String {
    return "git describe --tags --abbrev=4 HEAD".runCommand()
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "inc.combustion.framework"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        // It appears that this is unused. https://developer.android.com/reference/tools/gradle-api/7.4/com/android/build/api/dsl/LibraryBaseFlavor#targetSdk()
        // targetSdk = 34
        // It appears that this is unused.https://stackoverflow.com/questions/67803478/error-unresolved-reference-versioncode-in-build-gradle-kts
        // versionCode = 1
        // versionName = "0.0.3"

        buildConfigField("String", "VERSION_NAME", "\"" + getVersionName() + "\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs["debug"]
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    // https://stackoverflow.com/questions/75172062/how-to-replace-deprecated-packagingoptions-in-android-gradle-build-files
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // see: https://developer.android.com/studio/write/lint
    lint {
        // If set to true, turns off analysis progress reporting by lint.
        quiet = true
        // If set to true (default), stops the build if errors are found.
        abortOnError = true
        // If true, only report errors.
        ignoreWarnings = false
        // If true, lint also checks all dependencies as part of its analysis. Recommended for
        // projects consisting of an app with library dependencies.
        checkDependencies = true
    }
    buildFeatures {
        buildConfig = true
    }

    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions.freeCompilerArgs += listOf(
            "-opt-in=kotlin.ExperimentalUnsignedTypes",
            "-opt-in=com.juul.kable.ObsoleteKableApi",
            "-Xjvm-default=all-compatibility"
        )
    }

    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions.allWarningsAsErrors = name.contains("Release")
    }
}

dependencies {
    implementation(frameworkLibs.core.ktx)
    implementation(frameworkLibs.appcompat)
    implementation(frameworkLibs.lifecycle.runtime)
    implementation(frameworkLibs.kable.core)
    implementation(frameworkLibs.lifecycle.service)
    implementation(frameworkLibs.nordicsemi.dfu)
    coreLibraryDesugaring(frameworkLibs.android.desugarJdkLibs)
    androidTestImplementation(frameworkLibs.androidx.test.ext)
    androidTestImplementation(frameworkLibs.androidx.test.espresso)
    testImplementation(frameworkLibs.junit)
    testImplementation(frameworkLibs.kotlin.test.junit)
}

afterEvaluate {
    // TODO: Determine proper publishing configuration(s).
    /*
    publishing {
        publications {
            // Creates a Maven publication called "release".
            create<MavenPublication>("release") {
                // Applies the component for the release build variant.
                from(components["release"])

                // You can then customize attributes of the publication as shown below.
                groupId = "inc.combustion"
                artifactId = "framework"
                version = "0.1.0"
            }
            // Creates a Maven publication called "debug".
            create<MavenPublication>("debug") {
                // Applies the component for the debug build variant.
                from(components["debug"])

                // You can then customize attributes of the publication as shown below.
                groupId = "inc.combustion"
                artifactId = "framework"
                version = "0.1.0"
            }
        }
    }

     */
}
