package com.html2apk.converter.util

/**
 * Генерация и валидация Package ID (applicationId).
 */
internal object PackageIds {

    private val SEGMENT = Regex("[a-zA-Z][a-zA-Z0-9_]*")

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "var", "record", "sealed", "permits", "yield"
    )

    fun isValid(value: String): Boolean {
        if (value.isBlank() || value.length > 155) return false
        val segments = value.split('.')
        if (segments.size < 2) return false
        return segments.all { segment ->
            segment.isNotEmpty() && SEGMENT.matches(segment) && segment !in JAVA_KEYWORDS
        }
    }

    /** "My Game!" -> "mygame" (латиница, цифры, нижнее подчёркивание). */
    fun slug(value: String): String {
        val transliterated = transliterate(value.lowercase())
        val cleaned = buildString {
            transliterated.forEach { char ->
                when {
                    char in 'a'..'z' -> append(char)
                    char in '0'..'9' -> append(char)
                    char == '_' || char == '.' || char == ' ' || char == '-' -> append('_')
                }
            }
        }
        val collapsed = cleaned.split('_').filter { it.isNotEmpty() }.joinToString("_")
        return collapsed.ifEmpty { "app" }
    }

    fun suggestPackageId(appName: String): String {
        val base = slug(appName).ifEmpty { "app" }
        val segment = if (base.firstOrNull()?.isDigit() == true || base.firstOrNull()?.isLetter() != true) {
            "app_$base"
        } else {
            base
        }
        return "com.html2apk.$segment"
    }

    fun suggestFileName(appName: String): String {
        val cleaned = appName.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifEmpty { "WebApp" }
    }

    private fun transliterate(value: String): String {
        val builder = StringBuilder(value.length)
        value.forEach { char ->
            val replacement = map[char]
            if (replacement != null) builder.append(replacement) else builder.append(char)
        }
        return builder.toString()
    }

    private val map: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "",
        'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        'і' to "i", 'ї' to "i", 'є' to "e", 'ґ' to "g"
    )
}
