package com.html2apk.converter.core.binary

/**
 * Набор изменений, применяемых к бинарному AndroidManifest.xml.
 */
internal data class ManifestPatch(
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Int? = null,
    /** 0 = landscape, 1 = portrait, 4 = sensor, -1 = unspecified */
    val screenOrientation: Int? = null,
    val hardwareAccelerated: Boolean? = null,
    val label: String? = null
)

/**
 * Патчер бинарного AndroidManifest.xml (AXML).
 *
 * Работает без полной пересборки документа: меняются только значения атрибутов
 * (int-значения пишутся прямо в байты, строковые - в строковый пул, который затем
 * пересобирается с сохранением индексов). Все остальные chunk'и копируются как есть,
 * правится только поле size корневого ResXMLTree_header.
 */
internal object AxmlPatcher {

    private const val TYPE_XML = 0x0003
    private const val TYPE_START_ELEMENT = 0x0102

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11

    fun patch(axml: ByteArray, patch: ManifestPatch): ByteArray {
        require(axml.size > 8) { "AndroidManifest.xml слишком мал" }
        require(Bytes.u16(axml, 0) == TYPE_XML) {
            "AndroidManifest.xml не является бинарным AXML (type=0x${Bytes.u16(axml, 0).toString(16)})"
        }

        val poolOffset = Bytes.u16(axml, 2)
        require(poolOffset + StringPoolChunk.HEADER_SIZE <= axml.size) {
            "Повреждён заголовок AXML"
        }
        val pool = StringPoolChunk.parse(axml, poolOffset)

        val tailStart = pool.end
        require(tailStart <= axml.size) { "Повреждён строковый пул AXML" }
        val tail = axml.copyOfRange(tailStart, axml.size)

        val found = mutableSetOf<String>()
        walk(tail, pool) { element, attributes ->
            when (element) {
                "manifest" -> {
                    attributes.forEach { attribute ->
                        when (attribute.name) {
                            "package" -> {
                                patch.packageName?.let { value ->
                                    attribute.replaceString(pool, value)
                                    found += "package"
                                }
                            }

                            "versionName" -> {
                                patch.versionName?.let { value ->
                                    attribute.replaceString(pool, value)
                                    found += "versionName"
                                }
                            }

                            "versionCode" -> {
                                patch.versionCode?.let { value ->
                                    if (attribute.isStringValue) {
                                        attribute.replaceString(pool, value.toString())
                                    } else {
                                        attribute.writeInt(value)
                                    }
                                    found += "versionCode"
                                }
                            }
                        }
                    }
                }

                "application" -> {
                    attributes.forEach { attribute ->
                        if (attribute.name == "hardwareAccelerated") {
                            patch.hardwareAccelerated?.let { value ->
                                attribute.writeInt(if (value) -1 else 0)
                                found += "hardwareAccelerated"
                            }
                        }
                        if (attribute.name == "label" && attribute.isStringValue) {
                            patch.label?.let { value ->
                                attribute.replaceString(pool, value)
                                found += "label"
                            }
                        }
                    }
                }

                "activity" -> {
                    attributes.forEach { attribute ->
                        if (attribute.name == "screenOrientation") {
                            patch.screenOrientation?.let { value ->
                                attribute.writeInt(value)
                                found += "screenOrientation"
                            }
                        }
                        if (attribute.name == "label" && attribute.isStringValue) {
                            patch.label?.let { value ->
                                attribute.replaceString(pool, value)
                            }
                        }
                    }
                }
            }
        }

        require(patch.packageName == null || "package" in found) {
            "В AndroidManifest.xml шаблона не найден атрибут package"
        }
        require(patch.versionCode == null || "versionCode" in found) {
            "В AndroidManifest.xml шаблона не найден атрибут android:versionCode"
        }

        val newPool = pool.build()
        val total = 8 + newPool.size + tail.size
        val out = ByteArray(total)
        Bytes.w16(out, 0, TYPE_XML)
        Bytes.w16(out, 2, 8)
        Bytes.w32(out, 4, total)
        System.arraycopy(newPool, 0, out, 8, newPool.size)
        System.arraycopy(tail, 0, out, 8 + newPool.size, tail.size)
        return out
    }

    private fun walk(
        tail: ByteArray,
        pool: StringPoolChunk,
        onElement: (name: String, attributes: List<Attribute>) -> Unit
    ) {
        var offset = 0
        while (offset + 8 <= tail.size) {
            val type = Bytes.u16(tail, offset)
            val headerSize = Bytes.u16(tail, offset + 2)
            val size = Bytes.u32(tail, offset + 4)
            if (size < 8 || offset + size > tail.size) break

            if (type == TYPE_START_ELEMENT) {
                val extOffset = offset + headerSize
                if (extOffset + 20 <= offset + size) {
                    val nameIndex = Bytes.u32(tail, extOffset + 4)
                    val attributeStart = Bytes.u16(tail, extOffset + 8)
                    val attributeSize = Bytes.u16(tail, extOffset + 10)
                    val attributeCount = Bytes.u16(tail, extOffset + 12)
                    val name = pool.get(nameIndex)
                    if (name != null) {
                        val attributes = ArrayList<Attribute>(attributeCount)
                        for (index in 0 until attributeCount) {
                            val position = extOffset + attributeStart + index * attributeSize
                            if (position + 20 > tail.size) break
                            attributes.add(Attribute(tail, position, pool))
                        }
                        onElement(name, attributes)
                    }
                }
            }
            offset += size
        }
    }

    private class Attribute(
        private val buffer: ByteArray,
        private val position: Int,
        private val pool: StringPoolChunk
    ) {
        val name: String? = pool.get(Bytes.u32(buffer, position + 4))

        /** Индекс строки в пуле для rawValue, либо -1. */
        private val rawValue: Int = Bytes.u32(buffer, position + 8)

        private val dataType: Int = Bytes.u8(buffer, position + 15)
        private val data: Int = Bytes.u32(buffer, position + 16)

        val isStringValue: Boolean
            get() = dataType == TYPE_STRING || (dataType == 0 && rawValue >= 0)

        fun replaceString(pool: StringPoolChunk, value: String) {
            val target = when {
                dataType == TYPE_STRING && data >= 0 -> data
                rawValue >= 0 -> rawValue
                else -> throw IllegalStateException("Атрибут '$name' не является строкой, замена невозможна")
            }
            if (pool.get(target) == null) {
                throw IllegalStateException("Индекс строки $target вне пула для атрибута '$name'")
            }
            pool.replace(target, value)
            // rawValue должен ссылаться на ту же строку, что и typed value
            Bytes.w32(buffer, position + 8, target)
        }

        fun writeInt(value: Int) {
            Bytes.w32(buffer, position + 16, value)
            if (dataType != TYPE_INT_DEC && dataType != TYPE_INT_HEX) {
                Bytes.w8(buffer, position + 15, TYPE_INT_DEC)
            }
        }
    }
}
