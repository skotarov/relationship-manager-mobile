import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val defaultAccessToken = providers.gradleProperty("CALLREPORT_DEFAULT_ACCESS_TOKEN")
    .orElse(System.getenv("CALLREPORT_DEFAULT_ACCESS_TOKEN") ?: "")
    .get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val buildTimeUtc = Instant.now().toString()

android {
    namespace = "com.onlineimoti.calllog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.onlineimoti.calllog"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "DEFAULT_ACCESS_TOKEN", "\"$defaultAccessToken\"")
        buildConfigField("String", "BUILD_TIME_UTC", "\"$buildTimeUtc\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

setProperty("archivesBaseName", "callreport")

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.12.1")
}
