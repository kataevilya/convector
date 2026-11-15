package com.html2apk.converter.core.zip

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal sealed class Payload {
    class Inline(val bytes: ByteArray) : Payload()
    class FromFile(val file: File) : Payload()

    fun size(): Long = when (this) {
        is Inline -> bytes.size.toLong()
        is FromFile -> file.length()
    }

    fun openStream(): InputStream = when (this) {
        is Inline -> bytes.inputStream()
        is FromFile -> file.inputStream()
    }
}

/**
 * Собственный "zipalign": собирает APK так, чтобы некомпрессированные записи
 * (resources.arsc = 4 байта, нативные библиотеки .so = 4096 байт) начинались
 * с выровненного смещения. Требование Android 11+ для resources.arsc и
 * Android 15+ (16K page) для .so.
 *
 * Выравнивание делается через padding в extra-поле локального заголовка ZIP
 * (id 0xD935 - так же, как это делает утилита zipalign из build-tools).
 */
internal class ApkWriter {

    class Entry(
        val name: String,
        val payload: Payload,
        val stored: Boolean = false,
        val alignment: Int = 1
    )

    fun write(entries: List<Entry>, target: File) {
        val names = HashSet<String>()
        entries.forEach {
            require(it.name.isNotBlank() && !it.name.startsWith("/") && !it.name.contains("..")) {
                "Недопустимое имя записи APK: ${it.name}"
            }
            require(names.add(it.name)) { "Дубликат записи в APK: ${it.name}" }
        }

        target.parentFile?.mkdirs()
        val counter = CountingOutputStream(BufferedOutputStream(FileOutputStream(target), BUFFER_SIZE))
        ZipOutputStream(counter, Charsets.UTF_8).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            entries.forEach { entry ->
                val zipEntry = ZipEntry(entry.name)
                // Фиксированное время: DOS-формат, никаких extended-timestamp extra-полей.
                zipEntry.time = FIXED_TIME_MILLIS

                if (entry.stored) {
                    val crc = crcOf(entry.payload)
                    val size = entry.payload.size()
                    zipEntry.method = ZipEntry.STORED
                    zipEntry.size = size
                    zipEntry.compressedSize = size
                    zipEntry.crc = crc
                    if (entry.alignment > 1) {
                        val nameLength = entry.name.toByteArray(Charsets.UTF_8).size
                        val base = LOCAL_HEADER_SIZE + nameLength + ALIGN_EXTRA_HEADER
                        val remainder = (counter.count + base) % entry.alignment
                        val pad = ((entry.alignment - remainder) % entry.alignment).toInt()
                        zipEntry.extra = alignmentExtra(pad)
                    }
                } else {
                    zipEntry.method = ZipEntry.DEFLATED
                }

                zip.putNextEntry(zipEntry)
                entry.payload.openStream().use { input ->
                    input.copyTo(zip, BUFFER_SIZE)
                }
                zip.closeEntry()
            }
        }
    }

    private fun crcOf(payload: Payload): Long {
        val crc = CRC32()
        payload.openStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun alignmentExtra(pad: Int): ByteArray {
        val extra = ByteArray(ALIGN_EXTRA_HEADER + pad)
        extra[0] = 0x35
        extra[1] = 0xD9.toByte()
        extra[2] = (pad and 0xFF).toByte()
        extra[3] = ((pad ushr 8) and 0xFF).toByte()
        return extra
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 1 shl 16
        const val LOCAL_HEADER_SIZE = 30
        const val ALIGN_EXTRA_HEADER = 4
        const val FIXED_TIME_MILLIS = 1_600_000_000_000L
    }
}
