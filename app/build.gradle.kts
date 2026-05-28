import com.android.build.api.variant.ApplicationVariant
import java.io.File

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "2.2.21"
    kotlin("plugin.compose") version "2.2.21"
}

android {
    compileSdk = 35

    val configuredKeystore = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let {
        val candidate = File(it)
        if (candidate.isAbsolute) candidate else project.file(it)
    }
    val configuredStorePassword = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    val configuredKeyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
    val configuredKeyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    val releaseSigningConfigured = configuredKeystore != null
            && configuredStorePassword != null
            && configuredKeyAlias != null
            && configuredKeyPassword != null
    val requestedReleaseBuild = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
    if (requestedReleaseBuild && !releaseSigningConfigured) {
        throw GradleException("Release signing must be provided via KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD.")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = configuredKeystore
                storePassword = configuredStorePassword
                keyAlias = configuredKeyAlias
                keyPassword = configuredKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "helium314.keyboard.wisprboard"
        minSdk = 21
        targetSdk = 35
        versionCode = 6610
        versionName = "6.6.1"
        buildConfigField("boolean", "ALLOW_USER_SUPPLIED_JNI", "false")
        buildConfigField("boolean", "ENABLE_GESTURE_DATA_GATHERING", "false")
        manifestPlaceholders["gestureDataProviderEnabled"] = "false"
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("nouserlib") { // retained for compatibility; production builds disable external JNI by default
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        debug {
            // "normal" debug has minify for smaller APK to fit the GitHub 25 MB limit when zipped
            // and for better performance in case users want to install a debug APK
            isMinifyEnabled = true
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "ALLOW_USER_SUPPLIED_JNI", "true")
            buildConfigField("boolean", "ENABLE_GESTURE_DATA_GATHERING", "true")
            manifestPlaceholders["gestureDataProviderEnabled"] = "true"
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "ALLOW_USER_SUPPLIED_JNI", "true")
            buildConfigField("boolean", "ENABLE_GESTURE_DATA_GATHERING", "true")
            manifestPlaceholders["gestureDataProviderEnabled"] = "true"
        }
        base.archivesBaseName = "WisprBoard_" + defaultConfig.versionName
        // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "debug") {
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(getDefaultProguardFile("proguard-android.txt").absolutePath))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    // androidx
    implementation("androidx.core:core-ktx:1.16.0") // 1.17 requires SDK 36
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.security:security-crypto:1.0.0") // encrypted prefs for the API key

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // newer than 2025.11.01 contains androidx.compose.material:material-android:1.10.0, which requires minSdk 23
    // maybe it's possible to use tools:overrideLibrary="androidx.compose.material" as it's not used explicitly, but probably this is just going to crash
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("sh.calvin.reorderable:reorderable:2.4.3") // for easier re-ordering, todo: check 3.0.0
    implementation("com.github.skydoves:colorpicker-compose:1.1.3") // for user-defined colors

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:runner:1.6.2")
    testImplementation("androidx.test:core:1.6.1")
}
