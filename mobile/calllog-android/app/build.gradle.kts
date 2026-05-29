import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
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
val launcherIconSizes = linkedMapOf(
    "mipmap-mdpi" to 108,
    "mipmap-hdpi" to 162,
    "mipmap-xhdpi" to 216,
    "mipmap-xxhdpi" to 324,
    "mipmap-xxxhdpi" to 432,
)

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

fun bubblePath(cx: Double, cy: Double, rx: Double, ry: Double, tailLeft: Boolean): Path2D.Double {
    val path = Path2D.Double()
    path.append(Ellipse2D.Double(cx - rx, cy - ry, rx * 2.0, ry * 2.0), false)
    if (tailLeft) {
        path.moveTo(cx - rx * 0.55, cy + ry * 0.62)
        path.lineTo(cx - rx * 1.04, cy + ry * 1.08)
        path.lineTo(cx - rx * 0.28, cy + ry * 0.78)
    } else {
        path.moveTo(cx + rx * 0.55, cy + ry * 0.62)
        path.lineTo(cx + rx * 1.04, cy + ry * 1.08)
        path.lineTo(cx + rx * 0.28, cy + ry * 0.78)
    }
    path.closePath()
    return path
}

fun drawLauncherForegroundPng(targetSize: Int, outputFile: File) {
    val image = BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    val scale = targetSize / 432.0
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.scale(scale, scale)

    val dark = Color(17, 27, 39, 255)
    graphics.color = dark
    graphics.fill(bubblePath(270.0, 183.0, 92.0, 72.0, false))

    graphics.color = Color(0, 0, 0, 0)
    graphics.stroke = BasicStroke(34f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    graphics.color = dark
    graphics.draw(bubblePath(175.0, 238.0, 126.0, 88.0, true))

    graphics.dispose()
    outputFile.parentFile.mkdirs()
    ImageIO.write(image, "png", outputFile)
}

fun ensureLauncherForegroundPngs() {
    launcherIconSizes.forEach { (folder, size) ->
        drawLauncherForegroundPng(
            targetSize = size,
            outputFile = project.file("src/main/res/$folder/callreport_launcher_foreground.png"),
        )
    }
}

ensureLauncherForegroundPngs()

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
