package com.html2apk.converter.core.binary

/**
 * Минимальный набор little-endian операций над байтовыми буферами APK-структур.
 */
internal object Bytes {

    fun u8(b: ByteArray, offset: Int): Int = b[offset].toInt() and 0xFF

    fun u16(b: ByteArray, offset: Int): Int =
        u8(b, offset) or (u8(b, offset + 1) shl 8)

    fun u32(b: ByteArray, offset: Int): Int =
        u8(b, offset) or
            (u8(b, offset + 1) shl 8) or
            (u8(b, offset + 2) shl 16) or
            (u8(b, offset + 3) shl 24)

    fun w8(b: ByteArray, offset: Int, value: Int) {
        b[offset] = value.toByte()
    }

    fun w16(b: ByteArray, offset: Int, value: Int) {
        b[offset] = value.toByte()
        b[offset + 1] = (value ushr 8).toByte()
    }

    fun w32(b: ByteArray, offset: Int, value: Int) {
        b[offset] = value.toByte()
        b[offset + 1] = (value ushr 8).toByte()
        b[offset + 2] = (value ushr 16).toByte()
        b[offset + 3] = (value ushr 24).toByte()
    }

    fun aligned4(size: Int): Int = (size + 3) and 3.inv()

    fun pad4(source: ByteArray): ByteArray =
        if (source.size % 4 == 0) source else source.copyOf(aligned4(source.size))

    fun readUtf16(b: ByteArray, offset: Int): String {
        var p = offset
        var length = u16(b, p)
        p += 2
        if (length and 0x8000 != 0) {
            length = ((length and 0x7FFF) shl 16) or u16(b, p)
            p += 2
        }
        if (length <= 0) return ""
        return String(b, p, length * 2, Charsets.UTF_16LE)
    }

    fun readUtf8(b: ByteArray, offset: Int): String {
        var p = offset
        // "character length" в UTF-16 code units (не нужна, но занимает 1 или 2 байта)
        p += if (u8(b, p) and 0x80 != 0) 2 else 1
        var byteLength = u8(b, p)
        p += 1
        if (byteLength and 0x80 != 0) {
            byteLength = ((byteLength and 0x7F) shl 8) or u8(b, p)
            p += 1
        }
        if (byteLength <= 0) return ""
        return String(b, p, byteLength, Charsets.UTF_8)
    }
}
