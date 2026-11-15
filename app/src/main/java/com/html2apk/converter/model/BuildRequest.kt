package com.html2apk.converter.model

import java.io.File

enum class SourceKind {
    HTML,
    ZIP
}

enum class Orientation(val id: String, val title: String, val manifestValue: Int) {
    UNSPECIFIED("unspecified", "Как в системе", -1),
    PORTRAIT("portrait", "Портретная", 1),
    LANDSCAPE("landscape", "Ландшафтная", 0),
    SENSOR("sensor", "По датчику", 4);

    companion object {
        fun fromId(id: String): Orientation = entries.firstOrNull { it.id == id } ?: UNSPECIFIED
    }
}

data class BuildRequest(
    val sourceFile: File,
    val sourceKind: SourceKind,
    val appName: String,
    val packageId: String,
    val versionName: String,
    val versionCode: Int,
    val iconFile: File?,
    val fullscreen: Boolean,
    val zoom: Boolean,
    val hardwareAcceleration: Boolean,
    val orientation: Orientation
)

enum class BuildStep(val label: String) {
    UNPACK_TEMPLATE("Распаковка шаблона WebView"),
    PACK_WEB("Упаковка веб-файлов в assets/web"),
    PATCH_MANIFEST("Патчинг бинарного AndroidManifest.xml"),
    PATCH_RESOURCES("Замена названия в resources.arsc"),
    ICONS("Генерация иконок mdpi…xxxhdpi"),
    ALIGN("Сборка ZIP + zipalign (4/4096 байт)"),
    SIGN("Подпись APK: v1 + v2"),
    DONE("Готово")
}

data class BuildProgress(val step: BuildStep, val index: Int, val total: Int)
