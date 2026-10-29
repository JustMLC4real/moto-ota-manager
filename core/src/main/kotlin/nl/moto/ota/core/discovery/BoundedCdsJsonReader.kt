package nl.moto.ota.core.discovery

internal data class CdsJsonNumber(val text: String)

    /** JVM/Android-independent strict reader: bounded input, depth, values and strings. */
internal class BoundedCdsJsonReader(private val input: String) {
        private var position = 0
        private var values = 0

        fun readObject(): Map<String, Any?> {
            require(input.length <= CdsProtocol.MAX_RESPONSE_BYTES)
            val value = readValue(0)
            whitespace()
            require(position == input.length && value is Map<*, *>)
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        }

        private fun readValue(depth: Int): Any? {
            require(depth <= 12 && ++values <= 4096)
            whitespace()
            require(position < input.length)
            return when (input[position]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> numberValue()
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            position++
            whitespace()
            val map = linkedMapOf<String, Any?>()
            if (consume('}')) return map
            while (true) {
                whitespace()
                require(position < input.length && input[position] == '"')
                val key = stringValue()
                require(!map.containsKey(key))
                whitespace()
                require(consume(':'))
                map[key] = readValue(depth + 1)
                whitespace()
                if (consume('}')) return map
                require(consume(','))
            }
        }

        private fun arrayValue(depth: Int): List<Any?> {
            position++
            whitespace()
            val result = mutableListOf<Any?>()
            if (consume(']')) return result
            while (true) {
                result += readValue(depth + 1)
                whitespace()
                if (consume(']')) return result
                require(consume(','))
            }
        }

        private fun stringValue(): String {
            require(consume('"'))
            val result = StringBuilder()
            while (position < input.length) {
                val c = input[position++]
                if (c == '"') return result.toString()
                require(c >= ' ')
                if (c != '\\') result.append(c) else {
                    require(position < input.length)
                    when (val escaped = input[position++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(position + 4 <= input.length)
                            result.append(input.substring(position, position + 4).toInt(16).toChar())
                            position += 4
                        }
                        else -> error("Invalid JSON escape")
                    }
                }
                require(result.length <= 16_384)
            }
            error("Unterminated JSON string")
        }

        private fun numberValue(): CdsJsonNumber {
            val start = position
            if (position < input.length && input[position] == '-') position++
            require(position < input.length)
            if (input[position] == '0') position++ else {
                require(input[position] in '1'..'9')
                while (position < input.length && input[position] in '0'..'9') position++
            }
            if (consume('.')) {
                val digits = position
                while (position < input.length && input[position] in '0'..'9') position++
                require(position > digits)
            }
            if (position < input.length && input[position] in "eE") {
                position++
                if (position < input.length && input[position] in "+-") position++
                val digits = position
                while (position < input.length && input[position] in '0'..'9') position++
                require(position > digits)
            }
            require(position - start <= 64)
            return CdsJsonNumber(input.substring(start, position))
        }

        private fun literal(text: String, value: Any?): Any? {
            require(input.startsWith(text, position))
            position += text.length
            return value
        }

        private fun consume(c: Char): Boolean = if (position < input.length && input[position] == c) { position++; true } else false
        private fun whitespace() { while (position < input.length && input[position] in " \t\r\n") position++ }
    }
