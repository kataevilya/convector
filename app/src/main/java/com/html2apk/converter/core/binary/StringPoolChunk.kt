package com.html2apk.converter.core.binary

import java.io.ByteArrayOutputStream

/**
 * Разобранный и изменяемый RES_STRING_POOL_CHUNK (chunk type 0x0001).
 *
 * Используется и для бинарного AndroidManifest.xml (AXML), и для глобального
 * строкового пула resources.arsc - формат у них полностью идентичный.
 *
 * При пересборке пул всегда записывается в UTF-8, индексы строк сохраняются,
 * поэтому все ссылки (атрибуты XML, Res_value.data в arsc) остаются валидными.
 */
internal class StringPoolChunk(
    val strings: MutableList<String>,
    private val styleOffsets: IntArray,
    private val styleData: ByteArray?,
    val chunkStart: Int,
    val chunkSize: Int
) {

    val end: Int get() = chunkStart + chunkSize

    fun get(index: Int): String? = strings.getOrNull(index)

    fun replace(index: Int, value: String) {
        if (index in strings.indices) strings[index] = value
    }

    fun indexOfExact(value: String): Int = strings.indexOf(value)

    fun build(): ByteArray {
        val count = strings.size
        val styleCount = if (styleData != null) styleOffsets.size else 0

        val offsets = IntArray(count)
        val data = ByteArrayOutputStream()
        for (index in 0 until count) {
            offsets[index] = data.size()
            val value = strings[index]
            val encoded = value.toByteArray(Charsets.UTF_8)
            writeUtf8Length(data, value.length)
            writeUtf8Length(data, encoded.size)
            data.write(encoded)
            data.write(0)
        }

        val stringData = Bytes.pad4(data.toByteArray())
        val headerSize = HEADER_SIZE
        val stringsStart = headerSize + count * 4 + styleCount * 4
        val stylesStart = if (styleCount > 0) stringsStart + stringData.size else 0
        val styleBytes = if (styleCount > 0) Bytes.pad4(styleData!!) else ByteArray(0)
        val total = if (styleCount > 0) stylesStart + styleBytes.size else stringsStart + stringData.size

        val out = ByteArray(total)
        Bytes.w16(out, 0, TYPE)
        Bytes.w16(out, 2, headerSize)
        Bytes.w32(out, 4, total)
        Bytes.w32(out, 8, count)
        Bytes.w32(out, 12, styleCount)
        // UTF-8 flag. SORTED flag сбрасываем намеренно: после замены строк порядок
        // может нарушиться, а флаг сообщает фреймворку о бинарном поиске.
        Bytes.w32(out, 16, FLAG_UTF8)
        Bytes.w32(out, 20, stringsStart)
        Bytes.w32(out, 24, stylesStart)

        for (index in 0 until count) {
            Bytes.w32(out, headerSize + index * 4, offsets[index])
        }
        for (index in 0 until styleCount) {
            Bytes.w32(out, headerSize + count * 4 + index * 4, styleOffsets[index])
        }

        System.arraycopy(stringData, 0, out, stringsStart, stringData.size)
        if (styleCount > 0) {
            System.arraycopy(styleBytes, 0, out, stylesStart, styleBytes.size)
        }
        return out
    }

    companion object {
        const val TYPE = 0x0001
        const val HEADER_SIZE = 28
        private const val FLAG_UTF8 = 0x00000100

        fun parse(source: ByteArray, offset: Int): StringPoolChunk {
            require(offset + HEADER_SIZE <= source.size) { "Строковый пул обрезан" }
            require(Bytes.u16(source, offset) == TYPE) {
                "Ожидался строковый пул (0x0001), найден 0x${Bytes.u16(source, offset).toString(16)}"
            }
            val headerSize = Bytes.u16(source, offset + 2)
            val chunkSize = Bytes.u32(source, offset + 4)
            val stringCount = Bytes.u32(source, offset + 8)
            val styleCount = Bytes.u32(source, offset + 12)
            val flags = Bytes.u32(source, offset + 16)
            val stringsStart = Bytes.u32(source, offset + 20)
            val stylesStart = Bytes.u32(source, offset + 24)
            val utf8 = flags and FLAG_UTF8 != 0

            require(stringCount >= 0 && stringCount < 1_000_000) { "Некорректный stringCount: $stringCount" }
            require(offset + chunkSize <= source.size) { "Строковый пул выходит за границы файла" }

            val strings = ArrayList<String>(stringCount)
            for (index in 0 until stringCount) {
                val relative = Bytes.u32(source, offset + headerSize + index * 4)
                val position = offset + stringsStart + relative
                if (position < 0 || position >= source.size) {
                    strings.add("")
                    continue
                }
                strings.add(
                    try {
                        if (utf8) Bytes.readUtf8(source, position) else Bytes.readUtf16(source, position)
                    } catch (_: Throwable) {
                        ""
                    }
                )
            }

            val offsets = IntArray(styleCount)
            for (index in 0 until styleCount) {
                offsets[index] = Bytes.u32(source, offset + headerSize + stringCount * 4 + index * 4)
            }

            val styles = if (styleCount > 0 && stylesStart > 0 && offset + stylesStart < offset + chunkSize) {
                source.copyOfRange(offset + stylesStart, offset + chunkSize)
            } else {
                null
            }

            return StringPoolChunk(strings, offsets, styles, offset, chunkSize)
        }

        private fun writeUtf8Length(out: ByteArrayOutputStream, length: Int) {
            if (length > 0x7F) {
                out.write((0x80 or ((length ushr 8) and 0xFF)) and 0xFF)
                out.write(length and 0xFF)
            } else {
                out.write(length and 0xFF)
            }
        }
    }
}
