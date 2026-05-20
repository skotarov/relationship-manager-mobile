import java.time.OffsetDateTime
import java.time.ZoneOffset

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val githubRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
val buildTime = OffsetDateTime.now(ZoneOffset.UTC).toString()
val defaultAccessToken = System.getenv("CALLREPORT_DEFAULT_TOKEN").orEmpty()

android {
    namespace = "com.onlineimoti.calllog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.onlineimoti.calllog"
        minSdk = 29
        targetSdk = 35
        versionCode = githubRunNumber
        versionName = "0.2.$githubRunNumber"

        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "DEFAULT_ACCESS_TOKEN", "\"$defaultAccessToken\"")

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

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "callreport-${buildType.name}.apk"
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.12.1")
}
