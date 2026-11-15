package com.html2apk.converter.util

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Безопасная распаковка пользовательского ZIP (защита от zip-slip и zip-бомб).
 */
internal object ZipExtractor {

    private const val MAX_TOTAL_BYTES = 1_500_000_000L
    private const val MAX_ENTRIES = 60_000

    fun extract(archive: File, targetDir: File): File {
        targetDir.mkdirs()
        val root = targetDir.canonicalFile
        var total = 0L
        var count = 0

        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                if (shouldSkip(name)) continue

                count++
                if (count > MAX_ENTRIES) throw IOException("Слишком много файлов в архиве")

                val destination = File(root, name).canonicalFile
                if (!destination.path.startsWith(root.path + File.separator)) {
                    throw IOException("Небезопасный путь в архиве: ${entry.name}")
                }
                destination.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                }
                total += destination.length()
                if (total > MAX_TOTAL_BYTES) throw IOException("Архив слишком большой")
            }
        }
        return root
    }

    private fun shouldSkip(name: String): Boolean {
        if (name.isBlank()) return true
        if (name.startsWith("__MACOSX/")) return true
        val segments = name.split('/')
        return segments.any { it == "__MACOSX" || it == ".DS_Store" || it == "Thumbs.db" }
    }
}
