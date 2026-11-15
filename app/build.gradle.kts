import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** Иконка самого конвертера (генерируется на этапе сборки, без бинарников в git). */
val generatedIconRes = layout.buildDirectory.dir("generated/launcher-icons")

val generateLauncherIcons = tasks.register("generateLauncherIcons") {
    val outDir = generatedIconRes.get().asFile
    outputs.dir(outDir)
    doLast {
        System.setProperty("java.awt.headless", "true")
        val densities = linkedMapOf(
            "mdpi" to 48,
            "hdpi" to 72,
            "xhdpi" to 96,
            "xxhdpi" to 144,
            "xxxhdpi" to 192
        )
        for ((qualifier, size) in densities) {
            val dir = File(outDir, "mipmap-$qualifier")
            dir.mkdirs()
            val s = size.toFloat()
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.paint = GradientPaint(0f, 0f, Color(0x18, 0x2A, 0x46), s, s, Color(0x2E, 0x1B, 0x52))
            g.fillRoundRect(0, 0, size, size, (s * 0.26f).toInt(), (s * 0.26f).toInt())
            g.color = Color(0x7C, 0xC4, 0xFF)
            g.stroke = BasicStroke(s * 0.075f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawRoundRect(
                (s * 0.16f).toInt(), (s * 0.16f).toInt(),
                (s * 0.68f).toInt(), (s * 0.68f).toInt(),
                (s * 0.14f).toInt(), (s * 0.14f).toInt()
            )
            g.color = Color.WHITE
            g.drawLine((s * 0.40f).toInt(), (s * 0.36f).toInt(), (s * 0.27f).toInt(), (s * 0.50f).toInt())
            g.drawLine((s * 0.27f).toInt(), (s * 0.50f).toInt(), (s * 0.40f).toInt(), (s * 0.64f).toInt())
            g.drawLine((s * 0.60f).toInt(), (s * 0.36f).toInt(), (s * 0.73f).toInt(), (s * 0.50f).toInt())
            g.drawLine((s * 0.73f).toInt(), (s * 0.50f).toInt(), (s * 0.60f).toInt(), (s * 0.64f).toInt())
            g.dispose()
            ImageIO.write(image, "png", File(dir, "ic_launcher.png"))
        }
    }
}

/**
 * Копирует свежесобранный template-webview-release APK в assets/template.apk.
 * Благодаря этому `gradle :app:assembleRelease` всегда даёт рабочий конвертер.
 */
val templateAssetsDir = layout.buildDirectory.dir("generated/templateAssets")

val copyTemplateApk = tasks.register<Copy>("copyTemplateApk") {
    dependsOn(":template-webview:assembleRelease")
    from(project(":template-webview").layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "template.apk" }
    }
    into(templateAssetsDir)
}

val releaseStore = System.getenv("RELEASE_STORE_FILE")
    ?.takeIf { it.isNotBlank() }
    ?.let { File(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "com.html2apk.converter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.html2apk.converter"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets.getByName("main") {
        res.srcDir(generatedIconRes.get().asFile)
        assets.srcDir(templateAssetsDir.get().asFile)
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "html2apk"
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "html2apk"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                    ?: System.getenv("RELEASE_STORE_PASSWORD")
                    ?: "html2apk"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/*.kotlin_module"
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateLauncherIcons)
    dependsOn(copyTemplateApk)
}

/**
 * JVM-smoke-тест патчера: AXML + resources.arsc + выравнивание + подпись apksig v1/v2.
 * Ему нужен путь к свежесобранному шаблону.
 */
tasks.withType<Test>().configureEach {
    dependsOn(copyTemplateApk)
    systemProperty(
        "html2apk.template",
        layout.buildDirectory.dir("generated/templateAssets")
            .get().asFile.resolve("template.apk").absolutePath
    )
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.apksig)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
}
