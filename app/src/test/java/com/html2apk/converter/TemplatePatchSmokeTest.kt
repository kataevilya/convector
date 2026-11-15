package com.html2apk.converter

import com.html2apk.converter.core.binary.ArscPatcher
import com.html2apk.converter.core.binary.AxmlPatcher
import com.html2apk.converter.core.binary.Bytes
import com.html2apk.converter.core.binary.ManifestPatch
import com.html2apk.converter.core.sign.ApkSignerWrapper
import com.html2apk.converter.core.sign.SelfSignedCertificate
import com.html2apk.converter.core.sign.SigningKeyProvider
import com.html2apk.converter.core.zip.ApkWriter
import com.html2apk.converter.core.zip.Payload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Прогоняет на JVM ровно ту цепочку, которая работает на телефоне:
 * патч AXML -> патч resources.arsc -> поиск иконок по таблице ресурсов ->
 * сборка ZIP с выравниванием -> генерация RSA-ключа и X.509 -> подпись apksig v1+v2.
 *
 * Артефакты кладутся в app/build/smoke/, в CI их дополнительно проверяют
 * zipalign -c, apksigner verify и aapt2 dump badging.
 */
class TemplatePatchSmokeTest {

    private val work = File("build/smoke").apply { mkdirs() }

    @Test
    fun patchAlignAndSign() {
        val templatePath = System.getProperty("html2apk.template")
        assertNotNull("Не задано свойство html2apk.template", templatePath)
        val template = File(templatePath!!)
        assertTrue("template.apk не найден: $templatePath", template.isFile)

        val entries = readEntries(template)
        assertTrue("в шаблоне нет AndroidManifest.xml", "AndroidManifest.xml" in entries)
        assertTrue("в шаблоне нет resources.arsc", "resources.arsc" in entries)

        // ---- 1. AXML ----
        val manifest = entries.getValue("AndroidManifest.xml")
        val patchedManifest = AxmlPatcher.patch(
            manifest,
            ManifestPatch(
                packageName = "com.atary.smoketest",
                versionName = "9.9.9",
                versionCode = 4242,
                screenOrientation = 1,
                hardwareAccelerated = true
            )
        )
        assertEquals(0x0003, Bytes.u16(patchedManifest, 0))
        assertEquals(patchedManifest.size, Bytes.u32(patchedManifest, 4))
        entries["AndroidManifest.xml"] = patchedManifest

        // ---- 2. resources.arsc ----
        val arsc = entries.getValue("resources.arsc")
        val patchedArsc = ArscPatcher.replaceStringResource(arsc, setOf("app_name"), "My Smoke Test")
            ?: ArscPatcher.replaceAllStrings(arsc, "WebApp", "My Smoke Test")
        assertNotNull("app_name не найден в resources.arsc", patchedArsc)
        entries["resources.arsc"] = patchedArsc!!

        // ---- 3. иконки по данным таблицы ресурсов ----
        val iconEntries = ArscPatcher.findFileEntries(patchedArsc, "mipmap", setOf("ic_launcher"))
        assertTrue("mipmap/ic_launcher не найден в resources.arsc", iconEntries.isNotEmpty())
        assertEquals(
            "ожидаются 5 плотностей иконки",
            5,
            iconEntries.map { it.density }.distinct().size
        )
        iconEntries.forEach { icon ->
            assertTrue("файл иконки ${icon.path} отсутствует в APK", icon.path in entries)
        }

        // ---- 4. сборка ZIP с выравниванием ----
        val unsigned = File(work, "aligned.apk")
        val list = ArrayList<ApkWriter.Entry>(entries.size)
        list += ApkWriter.Entry("AndroidManifest.xml", Payload.Inline(patchedManifest))
        list += ApkWriter.Entry(
            "resources.arsc",
            Payload.Inline(patchedArsc),
            stored = true,
            alignment = 4
        )
        entries.forEach { (name, bytes) ->
            if (name == "AndroidManifest.xml" || name == "resources.arsc") return@forEach
            val native = name.endsWith(".so")
            list += ApkWriter.Entry(name, Payload.Inline(bytes), stored = native, alignment = if (native) 4096 else 1)
        }
        ApkWriter().write(list, unsigned)

        ZipFile(unsigned).use { zip ->
            val entry = zip.getEntry("resources.arsc")
            assertNotNull(entry)
            assertEquals("resources.arsc должен быть без сжатия", ZipEntry.STORED, entry.method)
            assertEquals(entry.size, entry.compressedSize)
        }
        assertEquals(
            "resources.arsc должен быть выровнен на 4 байта",
            0,
            dataOffsetOf(unsigned, "resources.arsc") % 4
        )

        // ---- 5. ключ + сертификат ----
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = SelfSignedCertificate.create(keyPair, "HTML2APK Smoke", "HTML2APK", 365)
        certificate.checkValidity()
        certificate.verify(keyPair.public)

        // ---- 6. подпись v1 + v2 ----
        val signed = File(work, "signed.apk")
        ApkSignerWrapper.sign(
            input = unsigned,
            output = signed,
            material = SigningKeyProvider.SigningMaterial(keyPair.private, certificate, "smoke"),
            minSdk = 24
        )
        assertTrue("подписанный APK пуст", signed.length() > unsigned.length())
        assertEquals(
            "apksig не должен смещать выровненные данные",
            0,
            dataOffsetOf(signed, "resources.arsc") % 4
        )

        ZipFile(signed).use { zip ->
            assertNotNull(zip.getEntry("AndroidManifest.xml"))
            assertNotNull("v1-подпись (META-INF/MANIFEST.MF) отсутствует", zip.getEntry("META-INF/MANIFEST.MF"))
        }
    }

    private fun readEntries(apk: File): LinkedHashMap<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipFile(apk).use { zip ->
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue
                if (entry.name.startsWith("META-INF/")) continue
                result[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        return result
    }

    /** Смещение данных записи = localHeader + 30 + nameLen + extraLen (через центральный каталог). */
    private fun dataOffsetOf(apk: File, entryName: String): Int {
        val bytes = apk.readBytes()

        var eocd = -1
        var index = bytes.size - 22
        while (index >= 0) {
            if (Bytes.u32(bytes, index) == 0x06054b50) {
                eocd = index
                break
            }
            index--
        }
        assertTrue("EOCD ZIP не найден в $apk", eocd >= 0)

        val centralSize = Bytes.u32(bytes, eocd + 12)
        val centralOffset = Bytes.u32(bytes, eocd + 16)
        assertEquals(0x02014b50, Bytes.u32(bytes, centralOffset))

        var position = centralOffset
        val centralEnd = centralOffset + centralSize
        while (position + 46 <= centralEnd) {
            assertEquals(0x02014b50, Bytes.u32(bytes, position))
            val nameLength = Bytes.u16(bytes, position + 28)
            val extraLength = Bytes.u16(bytes, position + 30)
            val commentLength = Bytes.u16(bytes, position + 32)
            val localOffset = Bytes.u32(bytes, position + 42)
            val name = String(bytes, position + 46, nameLength, Charsets.UTF_8)
            if (name == entryName) {
                val localNameLength = Bytes.u16(bytes, localOffset + 26)
                val localExtraLength = Bytes.u16(bytes, localOffset + 28)
                return localOffset + 30 + localNameLength + localExtraLength
            }
            position += 46 + nameLength + extraLength + commentLength
        }
        throw IllegalStateException("Запись $entryName не найдена в центральном каталоге $apk")
    }
}
