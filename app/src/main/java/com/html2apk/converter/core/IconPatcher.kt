package com.html2apk.converter.core

import com.html2apk.converter.core.binary.ArscPatcher

/**
 * Подбирает PNG для каждой файловой записи иконки.
 *
 * Ключевой момент: соответствие "плотность → файл" берётся из resources.arsc,
 * поэтому замена работает даже если AGP/aapt2 переименовал ресурсы
 * (`res/mipmap-mdpi-v4/ic_launcher.png` → `res/9w.png`).
 */
internal object IconPatcher {

    private val DENSITY_BY_DPI = mapOf(
        ArscPatcher.DENSITY_MEDIUM to "mdpi",
        ArscPatcher.DENSITY_HIGH to "hdpi",
        ArscPatcher.DENSITY_XHIGH to "xhdpi",
        ArscPatcher.DENSITY_XXHIGH to "xxhdpi",
        ArscPatcher.DENSITY_XXXHIGH to "xxxhdpi"
    )

    private val ICON_KEYS = setOf(
        "ic_launcher",
        "ic_launcher_round",
        "ic_launcher_foreground",
        "icon",
        "app_icon"
    )

    /** Путь записи в APK → новые PNG-байты. null, если ресурс не найден. */
    fun plan(arsc: ByteArray, icons: Map<String, ByteArray>): Map<String, ByteArray>? {
        val entries = ArscPatcher.findFileEntries(arsc, "mipmap", ICON_KEYS) +
            ArscPatcher.findFileEntries(arsc, "drawable", ICON_KEYS)
        if (entries.isEmpty()) return null

        val replacement = LinkedHashMap<String, ByteArray>()
        entries.forEach { entry ->
            val qualifier = DENSITY_BY_DPI[entry.density] ?: fallbackQualifier(entry.path)
            val png = icons[qualifier] ?: return@forEach
            replacement[entry.path] = png
        }
        return replacement.ifEmpty { null }
    }

    private fun fallbackQualifier(path: String): String {
        return when {
            path.contains("mdpi") -> "mdpi"
            path.contains("hdpi") -> "hdpi"
            path.contains("xhdpi") -> "xhdpi"
            path.contains("xxhdpi") -> "xxhdpi"
            else -> "xxxhdpi"
        }
    }
}
