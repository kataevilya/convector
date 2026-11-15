package com.html2apk.converter.core.sign

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Минимальный DER-кодировщик, достаточный для сборки самоподписанного X.509
 * сертификата на самом телефоне (без BouncyCastle и без интернета).
 */
internal object DerEncoder {

    fun sequence(vararg items: ByteArray): ByteArray = tlv(0x30, concat(items))

    fun set(vararg items: ByteArray): ByteArray = tlv(0x31, concat(items))

    fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())

    fun oid(dotted: String): ByteArray = tlv(0x06, encodeOid(dotted))

    fun utf8String(value: String): ByteArray = tlv(0x0C, value.toByteArray(Charsets.UTF_8))

    fun utcTime(timeMillis: Long): ByteArray {
        val date = Date(timeMillis)
        val calendarYear = SimpleDateFormat("yyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(date).toInt()

        return if (calendarYear >= 2050) {
            tlv(0x18, format(date, "yyyyMMddHHmmss'Z'"))
        } else {
            tlv(0x17, format(date, "yyMMddHHmmss'Z'"))
        }
    }

    fun nullValue(): ByteArray = tlv(0x05, ByteArray(0))

    fun bitString(value: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + value)

    fun explicit(tagNumber: Int, content: ByteArray): ByteArray = tlv(0xA0 + tagNumber, content)

    private fun format(date: Date, pattern: String): ByteArray {
        val formatter = SimpleDateFormat(pattern, Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(date).toByteArray(Charsets.US_ASCII)
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(tag)
        output.write(encodeLength(content.size))
        output.write(content)
        return output.toByteArray()
    }

    private fun encodeLength(length: Int): ByteArray {
        return when {
            length < 0x80 -> byteArrayOf(length.toByte())
            length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
            length < 0x10000 -> byteArrayOf(
                0x82.toByte(),
                (length ushr 8).toByte(),
                length.toByte()
            )

            else -> byteArrayOf(
                0x83.toByte(),
                (length ushr 16).toByte(),
                (length ushr 8).toByte(),
                length.toByte()
            )
        }
    }

    private fun encodeOid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        require(parts.size >= 2) { "Некорректный OID: $dotted" }
        val output = ByteArrayOutputStream()
        output.write((parts[0] * 40 + parts[1]).toInt())
        for (index in 2 until parts.size) {
            var value = parts[index]
            val stack = ArrayDeque<Int>()
            do {
                stack.addFirst((value and 0x7F).toInt())
                value = value shr 7
            } while (value > 0)
            stack.forEachIndexed { position, byte ->
                output.write(if (position == stack.size - 1) byte else byte or 0x80)
            }
        }
        return output.toByteArray()
    }

    private fun concat(items: Array<out ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        items.forEach { output.write(it) }
        return output.toByteArray()
    }
}
