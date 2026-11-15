package com.html2apk.converter.core

import com.html2apk.converter.core.zip.Payload
import com.html2apk.converter.model.SourceKind
import com.html2apk.converter.util.ZipExtractor
import java.io.File
import java.io.IOException

/**
 * Готовит содержимое assets/web из выбранного пользователем источника.
 */
internal object WebAssets {

    data class Prepared(
        val files: Map<String, Payload>,
        val startPath: String
    )

    private const val ASSET_PREFIX = "assets/web/"

    fun prepare(source: File, kind: SourceKind, workDir: File): Prepared {
        return when (kind) {
            SourceKind.HTML -> prepareSingleFile(source, workDir)
            SourceKind.ZIP -> prepareArchive(source, workDir)
        }
    }

    private fun prepareSingleFile(source: File, workDir: File): Prepared {
        val webDir = File(workDir, "web").apply { mkdirs() }
        val index = File(webDir, "index.html")
        source.copyTo(index, overwrite = true)
        return Prepared(mapOf("${ASSET_PREFIX}index.html" to Payload.FromFile(index)), "index.html")
    }

    private fun prepareArchive(source: File, workDir: File): Prepared {
        val rawDir = File(workDir, "web_raw").apply { mkdirs() }
        ZipExtractor.extract(source, rawDir)

        val root = stripSingleRoot(rawDir)
        val startPath = findStartDocument(root)
            ?: throw IOException("В архиве не найден index.html (или единственный .html файл)")

        val files = LinkedHashMap<String, Payload>()
        root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                val relative = relativePath(root, file)
                if (relative.isBlank() || relative.startsWith(".")) return@forEach
                files[ASSET_PREFIX + relative] = Payload.FromFile(file)
            }

        if (files.isEmpty()) throw IOException("Архив не содержит файлов")

        // гарантируем наличие index.html рядом с точкой входа
        if ("${ASSET_PREFIX}index.html" !in files) {
            val entry = files["$ASSET_PREFIX$startPath"]
            if (entry != null) files["${ASSET_PREFIX}index.html"] = entry
        }
        return Prepared(files, startPath)
    }

    private fun stripSingleRoot(dir: File): File {
        var current = dir
        repeat(2) {
            val children = current.listFiles() ?: return current
            val files = children.filter { it.isFile }
            val dirs = children.filter { it.isDirectory }
            if (files.isEmpty() && dirs.size == 1) {
                current = dirs.first()
            }
        }
        return current
    }

    private fun findStartDocument(root: File): String? {
        val topLevel = (root.listFiles() ?: emptyArray()).filter { it.isFile }
        topLevel.firstOrNull { it.name.equals("index.html", true) }?.let { return it.name }

        val topHtml = topLevel.filter { isHtml(it) }
        if (topHtml.size == 1) return topHtml.first().name

        val anyIndex = root.walkTopDown()
            .filter { it.isFile && it.name.equals("index.html", true) }
            .minByOrNull { depthOf(root, it) }
        if (anyIndex != null) return relativePath(root, anyIndex)

        val anyHtml = root.walkTopDown()
            .filter { it.isFile && isHtml(it) }
            .minByOrNull { depthOf(root, it) }
        return anyHtml?.let { relativePath(root, it) }
    }

    private fun isHtml(file: File): Boolean =
        file.extension.equals("html", true) || file.extension.equals("htm", true)

    private fun relativePath(root: File, file: File): String =
        file.absolutePath
            .removePrefix(root.absolutePath)
            .trimStart(File.separatorChar, '/')
            .replace(File.separatorChar, '/')

    private fun depthOf(root: File, file: File): Int = relativePath(root, file).count { it == '/' }
}
