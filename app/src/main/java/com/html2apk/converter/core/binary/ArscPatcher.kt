package com.html2apk.converter.core.binary

/**
 * Патчер resources.arsc.
 *
 * Задача: заменить строку строкового ресурса (по умолчанию `app_name`), на которую
 * ссылается `android:label` в манифесте. Меняется только глобальный строковый пул
 * (chunk 0x0001 в начале таблицы), все индексы строк сохраняются, поэтому
 * Res_value.data продолжает указывать на нужную запись.
 */
internal object ArscPatcher {

    private const val TYPE_TABLE = 0x0002
    private const val TYPE_PACKAGE = 0x0200
    private const val TYPE_TYPE = 0x0201
    private const val TYPE_STRING_VALUE = 0x03

    private const val FLAG_SPARSE = 0x01
    private const val FLAG_OFFSET16 = 0x02
    private const val ENTRY_FLAG_COMPLEX = 0x0001
    private const val ENTRY_FLAG_COMPACT = 0x0008

    /**
     * @return новые байты resources.arsc либо null, если ресурс с такими именами не найден.
     */
    fun replaceStringResource(
        arsc: ByteArray,
        resourceKeys: Set<String>,
        newValue: String
    ): ByteArray? {
        val targets = findStringValueIndices(arsc, resourceKeys)
        if (targets.isEmpty()) return null
        return rewritePool(arsc, targets) { newValue }
    }

    /**
     * Резервный режим: заменить все строки глобального пула, равные [oldValue].
     */
    fun replaceAllStrings(arsc: ByteArray, oldValue: String, newValue: String): ByteArray? {
        val pool = parseGlobalPool(arsc) ?: return null
        val indices = pool.strings.indices.filter { pool.strings[it] == oldValue }.toSet()
        if (indices.isEmpty()) return null
        return rewritePool(arsc, indices) { newValue }
    }

    private fun rewritePool(
        arsc: ByteArray,
        indices: Set<Int>,
        replaceValue: (Int) -> String
    ): ByteArray {
        val poolOffset = globalPoolOffset(arsc)
        val pool = StringPoolChunk.parse(arsc, poolOffset)
        indices.forEach { index -> pool.replace(index, replaceValue(index)) }
        val newPool = pool.build()
        val tail = arsc.copyOfRange(pool.end, arsc.size)
        val total = poolOffset + newPool.size + tail.size
        val out = ByteArray(total)
        System.arraycopy(arsc, 0, out, 0, poolOffset)
        Bytes.w32(out, 4, total)
        System.arraycopy(newPool, 0, out, poolOffset, newPool.size)
        System.arraycopy(tail, 0, out, poolOffset + newPool.size, tail.size)
        return out
    }

    private fun parseGlobalPool(arsc: ByteArray): StringPoolChunk? {
        return try {
            StringPoolChunk.parse(arsc, globalPoolOffset(arsc))
        } catch (_: Throwable) {
            null
        }
    }

    private fun globalPoolOffset(arsc: ByteArray): Int {
        require(arsc.size > 12 && Bytes.u16(arsc, 0) == TYPE_TABLE) {
            "resources.arsc повреждён: ожидался RES_TABLE_TYPE"
        }
        val headerSize = Bytes.u16(arsc, 2)
        require(Bytes.u16(arsc, headerSize) == StringPoolChunk.TYPE) {
            "resources.arsc повреждён: глобальный строковый пул не найден"
        }
        return headerSize
    }

    private fun findStringValueIndices(arsc: ByteArray, keys: Set<String>): Set<Int> {
        val targets = mutableSetOf<Int>()
        val poolOffset = try {
            globalPoolOffset(arsc)
        } catch (_: Throwable) {
            return targets
        }
        val globalPool = try {
            StringPoolChunk.parse(arsc, poolOffset)
        } catch (_: Throwable) {
            return targets
        }

        var offset = globalPool.end
        while (offset + 8 <= arsc.size) {
            val type = Bytes.u16(arsc, offset)
            val size = Bytes.u32(arsc, offset + 4)
            if (size < 8 || offset + size > arsc.size) break
            if (type == TYPE_PACKAGE) {
                collectFromPackage(arsc, offset, keys, targets)
            }
            offset += size
        }
        return targets.filter { it in globalPool.strings.indices }.toSet()
    }

    private fun collectFromPackage(
        arsc: ByteArray,
        pkgOffset: Int,
        keys: Set<String>,
        targets: MutableSet<Int>
    ) {
        val headerSize = Bytes.u16(arsc, pkgOffset + 2)
        val packageSize = Bytes.u32(arsc, pkgOffset + 4)
        if (headerSize < 288 || packageSize < headerSize) return
        val packageEnd = pkgOffset + packageSize
        if (packageEnd > arsc.size) return

        val typeStringsOffset = Bytes.u32(arsc, pkgOffset + 264)
        val keyStringsOffset = Bytes.u32(arsc, pkgOffset + 272)

        val typePool = if (typeStringsOffset > 0 && pkgOffset + typeStringsOffset < packageEnd) {
            runCatching { StringPoolChunk.parse(arsc, pkgOffset + typeStringsOffset) }.getOrNull()
        } else {
            null
        }
        val keyPool = if (keyStringsOffset > 0 && pkgOffset + keyStringsOffset < packageEnd) {
            runCatching { StringPoolChunk.parse(arsc, pkgOffset + keyStringsOffset) }.getOrNull()
        } else {
            null
        }
        if (typePool == null || keyPool == null) return

        var offset = pkgOffset + headerSize
        while (offset + 8 <= packageEnd) {
            val type = Bytes.u16(arsc, offset)
            val size = Bytes.u32(arsc, offset + 4)
            if (size < 8 || offset + size > packageEnd) break
            if (type == TYPE_TYPE) {
                collectFromType(arsc, offset, typePool, keyPool, keys, targets)
            }
            offset += size
        }
    }

    private fun collectFromType(
        arsc: ByteArray,
        typeOffset: Int,
        typePool: StringPoolChunk,
        keyPool: StringPoolChunk,
        keys: Set<String>,
        targets: MutableSet<Int>
    ) {
        val headerSize = Bytes.u16(arsc, typeOffset + 2)
        val size = Bytes.u32(arsc, typeOffset + 4)
        if (size < headerSize) return
        val typeEnd = typeOffset + size
        val typeId = Bytes.u8(arsc, typeOffset + 8)
        val flags = Bytes.u8(arsc, typeOffset + 9)
        val typeName = typePool.get(typeId - 1) ?: return
        if (typeName != "string") return

        val entryCount = Bytes.u32(arsc, typeOffset + 12)
        val entriesStart = Bytes.u32(arsc, typeOffset + 16)
        if (entryCount <= 0 || entryCount > 2_000_000) return

        val offsetsBase = typeOffset + headerSize
        for (index in 0 until entryCount) {
            var entryOffset = -1
            when {
                flags and FLAG_SPARSE != 0 -> {
                    if (offsetsBase + index * 4 + 4 > typeEnd) break
                    val value = Bytes.u32(arsc, offsetsBase + index * 4)
                    entryOffset = (value ushr 16) * 4
                }

                flags and FLAG_OFFSET16 != 0 -> {
                    if (offsetsBase + index * 2 + 2 > typeEnd) break
                    val value = Bytes.u16(arsc, offsetsBase + index * 2)
                    if (value != 0xFFFF) entryOffset = value * 4
                }

                else -> {
                    if (offsetsBase + index * 4 + 4 > typeEnd) break
                    val value = Bytes.u32(arsc, offsetsBase + index * 4)
                    if (value != -1) entryOffset = value
                }
            }
            if (entryOffset < 0) continue

            val entry = typeOffset + entriesStart + entryOffset
            if (entry + 8 > typeEnd) continue

            val entrySize = Bytes.u16(arsc, entry)
            val entryFlags = Bytes.u16(arsc, entry + 2)
            if (entryFlags and ENTRY_FLAG_COMPLEX != 0) continue
            if (entryFlags and ENTRY_FLAG_COMPACT != 0) continue

            val keyIndex = Bytes.u32(arsc, entry + 4)
            val keyName = keyPool.get(keyIndex) ?: continue
            if (keyName !in keys) continue

            val valueOffset = entry + entrySize
            if (valueOffset + 8 > typeEnd) continue
            val dataType = Bytes.u8(arsc, valueOffset + 3)
            val data = Bytes.u32(arsc, valueOffset + 4)
            if (dataType == TYPE_STRING_VALUE && data >= 0) targets += data
        }
    }
}
