import java.io.ByteArrayOutputStream
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("maven-publish")
}

fun String.runCommand(currentWorkingDir: File = file("./")): String {
    val byteOut = ByteArrayOutputStream()

    project.exec {
        workingDir = currentWorkingDir
        commandLine = this@runCommand.split("\\s".toRegex())
        standardOutput = byteOut
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("com.juul.kable:core:0.25.1")
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.8.0")
    implementation("no.nordicsemi.android:dfu:2.3.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.8.10")
}

afterEvaluate {
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
}
