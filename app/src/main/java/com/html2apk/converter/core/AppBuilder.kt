package com.html2apk.converter.core

import android.content.Context
import com.html2apk.converter.core.binary.ArscPatcher
import com.html2apk.converter.core.binary.AxmlPatcher
import com.html2apk.converter.core.binary.ManifestPatch
import com.html2apk.converter.core.sign.ApkSignerWrapper
import com.html2apk.converter.core.sign.SigningKeyProvider
import com.html2apk.converter.core.zip.ApkWriter
import com.html2apk.converter.core.zip.Payload
import com.html2apk.converter.model.BuildRequest
import com.html2apk.converter.model.BuildStep
import com.html2apk.converter.util.PackageIds
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Локальный "мини-линкер": HTML/JS/CSS/ZIP + template.apk -> подписанный APK.
 *
 * Никакого Gradle/aapt2 на телефоне: шаблон уже собран, мы только правим бинарные
 * структуры (AXML, resources.arsc), подменяем иконки/assets, выравниваем ZIP
 * и подписываем apksig'ом. Время сборки: 0.5-3 секунды.
 */
class AppBuilder(private val context: Context) {

    fun build(request: BuildRequest, onStep: (BuildStep) -> Unit): File {
        val work = File(context.cacheDir, "apkbuild_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            onStep(BuildStep.UNPACK_TEMPLATE)
            val template = File(work, "template.apk")
            extractTemplate(template)
            val entries = readTemplateEntries(template)

            onStep(BuildStep.PACK_WEB)
            val web = WebAssets.prepare(request.sourceFile, request.sourceKind, work)

            onStep(BuildStep.PATCH_MANIFEST)
            val manifestBytes = entries["AndroidManifest.xml"]
                ?: throw IllegalStateException("В шаблоне отсутствует AndroidManifest.xml")
            entries["AndroidManifest.xml"] = AxmlPatcher.patch(
                manifestBytes,
                ManifestPatch(
                    packageName = request.packageId,
                    versionName = request.versionName,
                    versionCode = request.versionCode,
                    screenOrientation = request.orientation.manifestValue,
                    hardwareAccelerated = request.hardwareAcceleration
                )
            )

            onStep(BuildStep.PATCH_RESOURCES)
            val arscBytes = entries["resources.arsc"]
                ?: throw IllegalStateException("В шаблоне отсутствует resources.arsc")
            entries["resources.arsc"] = patchAppName(arscBytes, request.appName)

            onStep(BuildStep.ICONS)
            applyIcons(entries, request)

            entries["assets/webapp_config.json"] = buildConfigJson(request, web.startPath).toByteArray()

            onStep(BuildStep.ALIGN)
            val unsigned = File(work, "unsigned.apk")
            writeApk(entries, web.files, unsigned)

            onStep(BuildStep.SIGN)
            val signed = File(work, "signed.apk")
            ApkSignerWrapper.sign(
                input = unsigned,
                output = signed,
                material = SigningKeyProvider(context).load(),
                minSdk = 24
            )

            val result = copyToOutput(signed, request.appName)
            onStep(BuildStep.DONE)
            return result
        } finally {
            work.deleteRecursively()
        }
    }

    private fun extractTemplate(target: File) {
        val assetName = "template.apk"
        try {
            context.assets.open(assetName).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
        } catch (t: Throwable) {
            throw IllegalStateException(
                "В assets приложения нет template.apk. Соберите его через :template-webview:assembleRelease " +
                    "(в GitHub Actions это делает workflow build-converter.yml).",
                t
            )
        }
        require(target.length() > 0) { "template.apk пустой" }
    }

    private fun readTemplateEntries(template: File): LinkedHashMap<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipFile(template).use { zip ->
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.startsWith("META-INF/")) continue
                if (name.startsWith("/") || name.contains("..")) continue
                result[name] = zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        check("AndroidManifest.xml" in result) { "Шаблон APK повреждён: нет AndroidManifest.xml" }
        check("resources.arsc" in result) { "Шаблон APK повреждён: нет resources.arsc" }
        return result
    }

    private fun patchAppName(arsc: ByteArray, appName: String): ByteArray {
        val byKey = ArscPatcher.replaceStringResource(arsc, setOf("app_name"), appName)
        if (byKey != null) return byKey

        val fallback = ArscPatcher.replaceAllStrings(arsc, TEMPLATE_DEFAULT_LABEL, appName)
        if (fallback != null) return fallback

        throw IllegalStateException(
            "Не удалось заменить название приложения в resources.arsc: ресурс app_name не найден"
        )
    }

    private fun applyIcons(entries: LinkedHashMap<String, ByteArray>, request: BuildRequest) {
        val iconFile = request.iconFile
        val icons = if (iconFile != null && iconFile.isFile) {
            IconProcessor.build(IconProcessor.decode(iconFile))
        } else {
            return
        }

        var replaced = 0
        val names = entries.keys.toList()
        names.forEach { name ->
            if (!ICON_ENTRY.containsMatchIn(name)) return@forEach
            val density = densityOf(name) ?: "xxxhdpi"
            val bytes = icons[density] ?: return@forEach
            entries[name] = bytes
            replaced++
        }

        require(replaced > 0) {
            "В шаблоне не найдены иконки res/mipmap-*/ic_launcher.png"
        }
    }

    private fun densityOf(entryName: String): String? {
        val qualifier = entryName
            .substringAfter("res/mipmap", "")
            .substringBefore("/")
            .removePrefix("-")
        if (qualifier.isEmpty()) return "mdpi"
        val tokens = qualifier.split('-')
        return tokens.firstOrNull { it in IconProcessor.DENSITIES.keys }
            ?: tokens.firstOrNull { it == "anydpi" }?.let { "xxxhdpi" }
    }

    private fun buildConfigJson(request: BuildRequest, startPath: String): String {
        return JSONObject().apply {
            put("fullscreen", request.fullscreen)
            put("zoom", request.zoom)
            put("hardwareAcceleration", request.hardwareAcceleration)
            put("orientation", request.orientation.id)
            put("startPath", startPath)
            put("appName", request.appName)
            put("packageId", request.packageId)
            put("versionName", request.versionName)
            put("versionCode", request.versionCode)
        }.toString(2)
    }

    private fun writeApk(
        templateEntries: Map<String, ByteArray>,
        webFiles: Map<String, Payload>,
        target: File
    ) {
        val list = ArrayList<ApkWriter.Entry>(templateEntries.size + webFiles.size + 8)

        list += ApkWriter.Entry("AndroidManifest.xml", Payload.Inline(templateEntries.getValue("AndroidManifest.xml")))
        list += ApkWriter.Entry(
            "resources.arsc",
            Payload.Inline(templateEntries.getValue("resources.arsc")),
            stored = true,
            alignment = 4
        )

        templateEntries.forEach { (name, bytes) ->
            if (name == "AndroidManifest.xml" || name == "resources.arsc") return@forEach
            if (name in webFiles) return@forEach
            val isNative = name.endsWith(".so")
            list += ApkWriter.Entry(
                name = name,
                payload = Payload.Inline(bytes),
                stored = isNative,
                alignment = if (isNative) 4096 else 1
            )
        }

        webFiles.forEach { (name, payload) ->
            list += ApkWriter.Entry(name, payload, stored = false)
        }

        ApkWriter().write(list, target)
    }

    private fun copyToOutput(signed: File, appName: String): File {
        val directory = context.getExternalFilesDir("builds") ?: File(context.filesDir, "builds")
        directory.mkdirs()
        val fileName = PackageIds.suggestFileName(appName) + ".apk"
        val target = File(directory, fileName)
        signed.copyTo(target, overwrite = true)
        return target
    }

    private companion object {
        const val TEMPLATE_DEFAULT_LABEL = "WebApp"
        val ICON_ENTRY = Regex("^res/mipmap(-[^/]+)?/ic_launcher\\.(png|webp|xml)$")
    }
}
