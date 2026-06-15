import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildTime = OffsetDateTime.now(ZoneOffset.UTC)
val buildTimeText = buildTime.toString()
val timeVersionCode =
    (buildTime.year % 100) * 10_000_000 +
        buildTime.dayOfYear * 10_000 +
        buildTime.hour * 100 +
        buildTime.minute
val appVersionCode = 1_000_000_000 + timeVersionCode
val defaultAccessToken = System.getenv("CALLREPORT_DEFAULT_TOKEN").orEmpty()
val fixedDebugKeystoreBase64File = rootProject.file("debug-signing/callreport-debug.keystore.base64")
val fixedDebugKeystoreFile = rootProject.layout.buildDirectory
    .file("generated-signing/callreport-debug.keystore")
    .get()
    .asFile

fun ensureFixedDebugKeystore(): File {
    require(fixedDebugKeystoreBase64File.isFile) {
        "Missing fixed debug keystore: ${fixedDebugKeystoreBase64File.path}"
    }
    fixedDebugKeystoreFile.parentFile.mkdirs()
    fixedDebugKeystoreFile.writeBytes(
        Base64.getMimeDecoder().decode(fixedDebugKeystoreBase64File.readText().trim())
    )
    return fixedDebugKeystoreFile
}

android {
    namespace = "com.onlineimoti.calllog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.onlineimoti.calllog"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = "0.3.$appVersionCode"
        resourceConfigurations += setOf("bg")

        buildConfigField("String", "BUILD_TIME", "\"$buildTimeText\"")
        buildConfigField("String", "DEFAULT_ACCESS_TOKEN", "\"$defaultAccessToken\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = ensureFixedDebugKeystore()
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = if (buildType.name == "release") {
                "relation-management-release-optimized.apk"
            } else {
                "relation-management-debug.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.material:material:1.12.0")
}
