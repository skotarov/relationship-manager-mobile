import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import javax.imageio.ImageIO

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
val launcherForegroundPngFile = project.file("src/main/res/drawable-nodpi/callreport_launcher_foreground.png")

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

fun ensureLauncherForegroundPng() {
    val size = 432
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.color = Color(17, 17, 17, 255)
    graphics.stroke = BasicStroke(22f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    fun path(vararg points: Pair<Double, Double>) {
        val p = Path2D.Double()
        p.moveTo(points[0].first, points[0].second)
        points.drop(1).forEach { p.lineTo(it.first, it.second) }
        graphics.draw(p)
    }

    path(95.0 to 205.0, 152.0 to 150.0, 205.0 to 200.0, 146.0 to 260.0, 95.0 to 205.0)
    path(337.0 to 205.0, 280.0 to 150.0, 227.0 to 200.0, 286.0 to 260.0, 337.0 to 205.0)
    path(152.0 to 192.0, 203.0 to 206.0, 230.0 to 192.0, 281.0 to 200.0, 296.0 to 257.0, 270.0 to 286.0, 228.0 to 249.0, 204.0 to 249.0, 184.0 to 281.0, 161.0 to 275.0, 148.0 to 258.0, 167.0 to 222.0)
    path(146.0 to 260.0, 182.0 to 300.0, 225.0 to 338.0, 258.0 to 311.0, 286.0 to 260.0)
    path(171.0 to 286.0, 150.0 to 306.0, 168.0 to 326.0, 190.0 to 305.0)
    path(200.0 to 313.0, 180.0 to 335.0, 202.0 to 354.0, 225.0 to 329.0)
    path(228.0 to 333.0, 211.0 to 354.0, 232.0 to 373.0, 254.0 to 346.0)
    path(267.0 to 286.0, 303.0 to 320.0)
    path(241.0 to 314.0, 276.0 to 347.0)

    graphics.dispose()
    launcherForegroundPngFile.parentFile.mkdirs()
    ImageIO.write(image, "png", launcherForegroundPngFile)
}

ensureLauncherForegroundPng()

android {
    namespace = "com.onlineimoti.calllog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.onlineimoti.calllog"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = "0.3.$appVersionCode"

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
            output.outputFileName = "relation-management-${buildType.name}.apk"
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
