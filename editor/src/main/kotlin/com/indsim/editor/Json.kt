package com.indsim.editor

/** JSON کوچک و بدون وابستگی خارجی (ذخیره/بازکردن پروژه، مشترک بین اندروید و تست JVM). */
object DocJson {
    fun encode(doc: Document): String {
        val sb = StringBuilder()
        sb.append("{\"v\":1,\"name\":").append(q(doc.name)).append(",\"placed\":[")
        doc.placed.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(q(p.id)).append(",\"type\":").append(q(p.type))
                .append(",\"x\":").append(p.x).append(",\"y\":").append(p.y).append(",\"rot\":").append(p.rot)
                .append(",\"params\":{")
            p.params.entries.forEachIndexed { j, e ->
                if (j > 0) sb.append(',')
                sb.append(q(e.key)).append(':').append(q(e.value))
            }
            sb.append("}}")
        }
        sb.append("],\"wires\":[")
        doc.wires.forEachIndexed { i, w ->
            if (i > 0) sb.append(',')
            sb.append("{\"a\":").append(q(w.a)).append(",\"b\":").append(q(w.b)).append(",\"lane\":").append(w.lane)
            if (w.color != null) sb.append(",\"color\":").append(w.color)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    fun decode(text: String): Document {
        val root = Parser(text).parse() as? Map<*, *> ?: error("root must be an object")
        val placed = (root["placed"] as? List<*> ?: emptyList<Any?>()).map {
            val m = it as Map<*, *>
            val params = (m["params"] as? Map<*, *> ?: emptyMap<Any?, Any?>())
                .entries.associate { e -> e.key as String to e.value as String }
            Placed(
                m["id"] as String, m["type"] as String,
                (m["x"] as Number).toInt(), (m["y"] as Number).toInt(),
                (m["rot"] as? Number)?.toInt() ?: 0, params,
            )
        }
        // مهاجرت خودکار فایل‌های نسخه‌ی قبلی: شماره‌ی قدیمی ترمینال شستی‌ها را به IEC تبدیل کن.
        val types = placed.associate { it.id to it.type }
        fun migrate(pin: String): String {
            val owner = pin.substringBefore(':')
            val terminal = pin.substringAfter(':', "")
            val mapped = when (types[owner]) {
                "pb_no" -> mapOf("3" to "13", "4" to "14")[terminal]
                "pb_nc", "estop" -> mapOf("1" to "21", "2" to "22")[terminal]
                "pb_dual" -> mapOf("1" to "11", "2" to "12", "3" to "13", "4" to "14")[terminal]
                else -> null
            }
            return if (mapped == null) pin else "$owner:$mapped"
        }
        val wires = (root["wires"] as? List<*> ?: emptyList<Any?>()).map {
            val m = it as Map<*, *>
            Wire(migrate(m["a"] as String), migrate(m["b"] as String), (m["lane"] as? Number)?.toInt() ?: 0, (m["color"] as? Number)?.toInt())
        }
        return Document(root["name"] as? String ?: "Untitled", placed, wires)
    }

    private fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
        return sb.append('"').toString()
    }

    private class Parser(val s: String) {
        var i = 0

        fun parse(): Any? {
            val v = value()
            ws()
            require(i == s.length) { "trailing data at $i" }
            return v
        }

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
            }
        }

        private fun lit(w: String, v: Any?): Any? {
            require(s.startsWith(w, i)) { "bad literal at $i" }
            i += w.length
            return v
        }

        private fun num(): Number {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDouble()
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> error("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value()
                ws()
                when (s[i++]) { ',' -> {}; ']' -> return l; else -> error("expected , or ] at ${i - 1}") }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws()
                require(s[i++] == ':') { "expected : at ${i - 1}" }
                m[k] = value()
                ws()
                when (s[i++]) { ',' -> {}; '}' -> return m; else -> error("expected , or } at ${i - 1}") }
            }
        }
    }
}
