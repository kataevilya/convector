package com.html2apk.converter.core.binary

/**
 * Патчер resources.arsc.
 *
 * Возможности:
 *  - замена строкового ресурса (по умолчанию `app_name`), на который ссылается
 *    `android:label`;
 *  - поиск файловых записей ресурса (например, `mipmap/ic_launcher`), чтобы
 *    подменить иконки по ПУТИ ИЗ ТАБЛИЦЫ, а не по имени файла (AGP умеет
 *    переименовывать ресурсы в `res/9w.png`, и тогда поиск по имени не работает);
 *  - определение плотности (dpi) из ResTable_config каждой конфигурации.
 *
 * Меняется только глобальный строковый пул (chunk 0x0001 в начале таблицы):
 * индексы строк сохраняются, поэтому все Res_value.data остаются валидными,
 * а весь остальной файл просто сдвигается. Правится лишь поле size заголовка.
 */
internal object ArscPatcher {

    private const val TYPE_TABLE = 0x0002
    private const val TYPE_PACKAGE = 0x0200
    private const val TYPE_TYPE = 0x0201

    const val TYPE_STRING = 0x03

    const val DENSITY_MEDIUM = 160
    const val DENSITY_HIGH = 240
    const val DENSITY_XHIGH = 320
    const val DENSITY_XXHIGH = 480
    const val DENSITY_XXXHIGH = 640

    private const val FLAG_SPARSE = 0x01
    private const val FLAG_OFFSET16 = 0x02
    private const val ENTRY_FLAG_COMPLEX = 0x0001
    private const val ENTRY_FLAG_COMPACT = 0x0008

    /**
     * Файловая запись ресурса: путь внутри APK + плотность из конфигурации.
     */
    data class FileEntry(val path: String, val density: Int)

    /**
     * @return новые байты resources.arsc либо null, если ресурс с такими именами не найден.
     */
    fun replaceStringResource(
        arsc: ByteArray,
        resourceKeys: Set<String>,
        newValue: String
    ): ByteArray? {
        val table = Table(arsc)
        val indices = mutableSetOf<Int>()
        table.forEachEntry("string", resourceKeys) { type, data, _ ->
            if (type == TYPE_STRING && data in table.globalPool.strings.indices) indices += data
        }
        if (indices.isEmpty()) return null
        indices.forEach { table.globalPool.replace(it, newValue) }
        return table.rewrite()
    }

    /**
     * Резервный режим: заменить все строки глобального пула, равные [oldValue].
     */
    fun replaceAllStrings(arsc: ByteArray, oldValue: String, newValue: String): ByteArray? {
        val table = Table(arsc)
        val hits = table.globalPool.strings.indices.filter { table.globalPool.strings[it] == oldValue }
        if (hits.isEmpty()) return null
        hits.forEach { table.globalPool.replace(it, newValue) }
        return table.rewrite()
    }

    /**
     * Находит файлы ресурсов указанного типа. Например
     * `findFileEntries(arsc, "mipmap", setOf("ic_launcher"))` вернёт
     * `[res/9w.png @160dpi, res/yn.png @240dpi, ...]`.
     */
    fun findFileEntries(
        arsc: ByteArray,
        typeName: String,
        resourceKeys: Set<String>
    ): List<FileEntry> {
        val table = Table(arsc)
        val result = ArrayList<FileEntry>()
        table.forEachEntry(typeName, resourceKeys) { type, data, density ->
            if (type == TYPE_STRING) {
                val path = table.globalPool.get(data)
                if (path != null && path.isNotEmpty()) result += FileEntry(path, density)
            }
        }
        return result
    }

    private class Table(val bytes: ByteArray) {

        val globalPoolOffset: Int
        val globalPool: StringPoolChunk

        init {
            require(bytes.size > 12 && Bytes.u16(bytes, 0) == TYPE_TABLE) {
                "resources.arsc повреждён: ожидался RES_TABLE_TYPE"
            }
            val headerSize = Bytes.u16(bytes, 2)
            require(headerSize >= 12 && headerSize + 8 <= bytes.size) { "Заголовок resources.arsc повреждён" }
            require(Bytes.u16(bytes, headerSize) == StringPoolChunk.TYPE) {
                "resources.arsc повреждён: глобальный строковый пул не найден"
            }
            globalPoolOffset = headerSize
            globalPool = StringPoolChunk.parse(bytes, globalPoolOffset)
        }

        fun rewrite(): ByteArray {
            val newPool = globalPool.build()
            val tail = bytes.copyOfRange(globalPool.end, bytes.size)
            val total = globalPoolOffset + newPool.size + tail.size
            val out = ByteArray(total)
            System.arraycopy(bytes, 0, out, 0, globalPoolOffset)
            Bytes.w32(out, 4, total)
            System.arraycopy(newPool, 0, out, globalPoolOffset, newPool.size)
            System.arraycopy(tail, 0, out, globalPoolOffset + newPool.size, tail.size)
            return out
        }

        fun forEachEntry(
            typeName: String,
            keys: Set<String>,
            action: (type: Int, data: Int, density: Int) -> Unit
        ) {
            var offset = globalPool.end
            while (offset + 8 <= bytes.size) {
                val chunkType = Bytes.u16(bytes, offset)
                val size = Bytes.u32(bytes, offset + 4)
                if (size < 8 || offset + size > bytes.size) break
                if (chunkType == TYPE_PACKAGE) {
                    walkPackage(offset, typeName, keys, action)
                }
                offset += size
            }
        }

        private fun walkPackage(
            pkgOffset: Int,
            typeName: String,
            keys: Set<String>,
            action: (Int, Int, Int) -> Unit
        ) {
            val headerSize = Bytes.u16(bytes, pkgOffset + 2)
            val packageSize = Bytes.u32(bytes, pkgOffset + 4)
            // ResTable_package: header(8) + id(4) + name[128](256) + typeStrings(268) + ...
            if (headerSize < 288 || packageSize < headerSize) return
            val packageEnd = pkgOffset + packageSize
            if (packageEnd > bytes.size) return

            val typeStringsOffset = Bytes.u32(bytes, pkgOffset + 268)
            val keyStringsOffset = Bytes.u32(bytes, pkgOffset + 276)

            val typePool = if (typeStringsOffset > 0 && pkgOffset + typeStringsOffset < packageEnd) {
                runCatching { StringPoolChunk.parse(bytes, pkgOffset + typeStringsOffset) }.getOrNull()
            } else {
                null
            }
            val keyPool = if (keyStringsOffset > 0 && pkgOffset + keyStringsOffset < packageEnd) {
                runCatching { StringPoolChunk.parse(bytes, pkgOffset + keyStringsOffset) }.getOrNull()
            } else {
                null
            }
            if (typePool == null || keyPool == null) return

            var offset = pkgOffset + headerSize
            while (offset + 8 <= packageEnd) {
                val chunkType = Bytes.u16(bytes, offset)
                val size = Bytes.u32(bytes, offset + 4)
                if (size < 8 || offset + size > packageEnd) break
                if (chunkType == TYPE_TYPE) {
                    walkType(offset, typePool, keyPool, typeName, keys, action)
                }
                offset += size
            }
        }

        private fun walkType(
            typeOffset: Int,
            typePool: StringPoolChunk,
            keyPool: StringPoolChunk,
            typeName: String,
            keys: Set<String>,
            action: (Int, Int, Int) -> Unit
        ) {
            val headerSize = Bytes.u16(bytes, typeOffset + 2)
            val size = Bytes.u32(bytes, typeOffset + 4)
            if (size < headerSize || typeOffset + size > bytes.size) return
            val typeEnd = typeOffset + size

            val typeId = Bytes.u8(bytes, typeOffset + 8)
            val flags = Bytes.u8(bytes, typeOffset + 9)
            if (typePool.get(typeId - 1) != typeName) return

            val entryCount = Bytes.u32(bytes, typeOffset + 12)
            val entriesStart = Bytes.u32(bytes, typeOffset + 16)
            if (entryCount <= 0 || entryCount > 2_000_000) return

            // ResTable_config начинается сразу за 20-байтным заголовком типа,
            // density лежит в screenType: orientation(0), touchscreen(1), density(2..3)
            val density = if (typeOffset + 34 + 2 <= typeEnd && headerSize >= 20) {
                Bytes.u16(bytes, typeOffset + 34)
            } else {
                0
            }

            val offsetsBase = typeOffset + headerSize
            for (index in 0 until entryCount) {
                var entryOffset = -1
                when {
                    flags and FLAG_SPARSE != 0 -> {
                        if (offsetsBase + index * 4 + 4 > typeEnd) break
                        val value = Bytes.u32(bytes, offsetsBase + index * 4)
                        entryOffset = (value ushr 16) * 4
                    }

                    flags and FLAG_OFFSET16 != 0 -> {
                        if (offsetsBase + index * 2 + 2 > typeEnd) break
                        val value = Bytes.u16(bytes, offsetsBase + index * 2)
                        if (value != 0xFFFF) entryOffset = value * 4
                    }

                    else -> {
                        if (offsetsBase + index * 4 + 4 > typeEnd) break
                        val value = Bytes.u32(bytes, offsetsBase + index * 4)
                        if (value != -1) entryOffset = value
                    }
                }
                if (entryOffset < 0) continue

                val entry = typeOffset + entriesStart + entryOffset
                if (entry + 8 > typeEnd) continue

                val entrySize = Bytes.u16(bytes, entry)
                val entryFlags = Bytes.u16(bytes, entry + 2)
                if (entryFlags and ENTRY_FLAG_COMPLEX != 0) continue
                if (entryFlags and ENTRY_FLAG_COMPACT != 0) continue

                val keyIndex = Bytes.u32(bytes, entry + 4)
                val keyName = keyPool.get(keyIndex) ?: continue
                if (keyName !in keys) continue

                val valueOffset = entry + entrySize
                if (valueOffset + 8 > typeEnd) continue
                val dataType = Bytes.u8(bytes, valueOffset + 3)
                val data = Bytes.u32(bytes, valueOffset + 4)
                action(dataType, data, density)
            }
        }
    }
}
