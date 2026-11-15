import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * Иконка шаблона генерируется прямо в Gradle-сборке (Java2D), чтобы в репозитории
 * не было бинарных файлов. Конвертер на телефоне потом перезапишет эти PNG.
 */
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
            g.paint = GradientPaint(0f, 0f, Color(0x39, 0x5A, 0xF5), s, s, Color(0x8A, 0x2B, 0xE2))
            g.fillRoundRect(0, 0, size, size, (s * 0.26f).toInt(), (s * 0.26f).toInt())
            g.color = Color.WHITE
            g.stroke = BasicStroke(s * 0.085f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawPolyline(
                intArrayOf((s * 0.40f).toInt(), (s * 0.26f).toInt(), (s * 0.40f).toInt()),
                intArrayOf((s * 0.32f).toInt(), (s * 0.50f).toInt(), (s * 0.68f).toInt()), 3
            )
            g.drawPolyline(
                intArrayOf((s * 0.60f).toInt(), (s * 0.74f).toInt(), (s * 0.60f).toInt()),
                intArrayOf((s * 0.32f).toInt(), (s * 0.50f).toInt(), (s * 0.68f).toInt()), 3
            )
            g.dispose()
            ImageIO.write(image, "png", File(dir, "ic_launcher.png"))
        }
    }
}

android {
    namespace = "com.template.webapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.template.webapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets.getByName("main") {
        res.srcDir(generatedIconRes)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/*.version",
            "META-INF/*.kotlin_module"
        )
    }
}

tasks.named("preBuild").configure { dependsOn(generateLauncherIcons) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
}
