package dev.ksql.postgres

import dev.ksql.SqlCodec

/**
 * PostgreSQL's `hstore` extension, as a map. Register it for columns to decode by themselves:
 * `CodecRegistry.Global.register(HstoreCodec)`.
 */
@Suppress("UNCHECKED_CAST")
object HstoreCodec : SqlCodec<Map<String, String?>>(Map::class as kotlin.reflect.KClass<Map<String, String?>>, setOf("hstore")) {
    override fun encode(value: Map<String, String?>): Any = value.entries.joinToString(", ") { (k, v) ->
        quote(k) + "=>" + if (v == null) "NULL" else quote(v)
    }

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Parses `"key"=>"value", "other"=>NULL`, which is how the server always writes it. */
    override fun decode(value: Any): Map<String, String?> {
        val text = value.toString()
        val result = LinkedHashMap<String, String?>()
        var pos = 0

        fun fail(): Nothing = throw IllegalArgumentException("not an hstore value")

        fun quoted(): String {
            if (text.getOrNull(pos) != '"') fail()
            pos++
            val out = StringBuilder()
            while (true) {
                when (val c = text.getOrNull(pos++) ?: fail()) {
                    '"' -> return out.toString()
                    '\\' -> out.append(text.getOrNull(pos++) ?: fail())
                    else -> out.append(c)
                }
            }
        }

        while (pos < text.length) {
            val key = quoted()
            if (!text.startsWith("=>", pos)) fail()
            pos += 2
            if (text.startsWith("NULL", pos)) {
                result[key] = null
                pos += 4
            } else {
                result[key] = quoted()
            }
            if (text.startsWith(", ", pos)) pos += 2 else if (pos < text.length) fail()
        }
        return result
    }
}
