package dev.ksql.postgres

import dev.ksql.IdentifierCase
import dev.ksql.IsolationLevel
import dev.ksql.MetadataQueries
import dev.ksql.SqlDialect
import dev.ksql.TranslatedSql

internal object PgDialect : SqlDialect {
    override val productName = "PostgreSQL"
    override val identifierCase get() = IdentifierCase.LOWER
    override fun parameterMarker(index: Int) = "$$index"
    override fun parameterIndex(name: String): Int? =
        if (name.startsWith("$")) name.substring(1).toIntOrNull()?.takeIf { it >= 1 }?.minus(1) else null

    override fun translatePositional(sql: String): TranslatedSql {
        var count = 0
        val out = StringBuilder(sql.length + 8)
        forEachSegment(sql) { start, end, isCode ->
            if (!isCode) out.append(sql, start, end)
            else {
                var i = start
                while (i < end) {
                    val c = sql[i]
                    when {
                        c != '?' -> { out.append(c); i++ }
                        // `??` is the JDBC escape for the `?` operator.
                        i + 1 < end && sql[i + 1] == '?' -> { out.append('?'); i += 2 }
                        else -> { out.append('$').append(++count); i++ }
                    }
                }
            }
        }
        return TranslatedSql(out.toString(), count)
    }

    override fun parameterCount(sql: String): Int {
        var max = 0
        forEachSegment(sql) { start, end, isCode ->
            if (isCode) {
                var i = start
                while (i < end) {
                    if (sql[i] == '$' && i + 1 < end && sql[i + 1].isDigit() && (i == 0 || !isIdentifierPart(sql[i - 1]))) {
                        var j = i + 1
                        while (j < end && sql[j].isDigit()) j++
                        max = maxOf(max, sql.substring(i + 1, j).toIntOrNull() ?: 0)
                        i = j
                    } else i++
                }
            }
        }
        return max
    }

    /** Splits SQL into executable code and opaque text (string literals, quoted identifiers, comments, dollar quotes). */
    private inline fun forEachSegment(sql: String, action: (start: Int, end: Int, isCode: Boolean) -> Unit) {
        var from = 0
        var i = 0
        while (i < sql.length) {
            val end = opaqueEnd(sql, i)
            if (end > i) {
                if (i > from) action(from, i, true)
                action(i, end, false)
                from = end
                i = end
            } else i++
        }
        if (sql.length > from) action(from, sql.length, true)
    }

    /** End of the opaque text starting at [start], or [start] if there is none. */
    private fun opaqueEnd(sql: String, start: Int): Int {
        val n = sql.length
        when (sql[start]) {
            '\'' -> {
                val backslashEscapes = start > 0 && (sql[start - 1] == 'E' || sql[start - 1] == 'e') &&
                    (start < 2 || !isIdentifierPart(sql[start - 2]))
                var j = start + 1
                while (j < n) when {
                    backslashEscapes && sql[j] == '\\' -> j += 2
                    sql[j] != '\'' -> j++
                    j + 1 < n && sql[j + 1] == '\'' -> j += 2
                    else -> return j + 1
                }
                return n
            }
            '"' -> {
                var j = start + 1
                while (j < n) when {
                    sql[j] != '"' -> j++
                    j + 1 < n && sql[j + 1] == '"' -> j += 2
                    else -> return j + 1
                }
                return n
            }
            '-' -> {
                if (sql.getOrNull(start + 1) != '-') return start
                val eol = sql.indexOf('\n', start)
                return if (eol < 0) n else eol + 1
            }
            '/' -> {
                if (sql.getOrNull(start + 1) != '*') return start
                var depth = 1
                var j = start + 2
                while (j < n && depth > 0) when {
                    sql.startsWith("/*", j) -> { depth++; j += 2 }
                    sql.startsWith("*/", j) -> { depth--; j += 2 }
                    else -> j++
                }
                return j
            }
            '$' -> {
                if (start > 0 && isIdentifierPart(sql[start - 1])) return start
                var j = start + 1
                if (j < n && sql[j].isDigit()) return start // a parameter like $1
                while (j < n && sql[j] != '$' && isIdentifierPart(sql[j])) j++
                if (j >= n || sql[j] != '$') return start
                val tag = sql.substring(start, j + 1)
                val close = sql.indexOf(tag, j + 1)
                return if (close < 0) n else close + tag.length
            }
            else -> return start
        }
    }

    private fun isIdentifierPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    override fun withGeneratedKeys(sql: String, columns: List<String>): String? {
        val body = sql.trim().trimEnd(';').trimEnd()
        val first = body.takeWhile { it.isLetter() }.uppercase()
        if (first !in setOf("INSERT", "UPDATE", "DELETE", "MERGE")) return null
        if (Regex("\\breturning\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)) return body
        val list = if (columns.isEmpty()) "*" else columns.joinToString(", ") { quoteIdentifier(it) }
        return "$body RETURNING $list"
    }

    override fun setIsolationSql(level: IsolationLevel) = "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL ${level.sql}"
    override fun showIsolationSql() = "SHOW transaction_isolation"
    override fun setReadOnlySql(readOnly: Boolean) =
        "SET SESSION CHARACTERISTICS AS TRANSACTION ${if (readOnly) "READ ONLY" else "READ WRITE"}"
    override fun setStatementTimeoutSql(millis: Long) = "SET statement_timeout = $millis"
    override fun setLockTimeoutSql(millis: Long) = "SET lock_timeout = $millis"
    override fun setSchemaSql(schema: String) = "SET search_path TO ${quoteIdentifier(schema)}"
    override fun currentSchemaSql() = "SELECT current_schema()"
    override fun currentCatalogSql() = "SELECT current_database()"
    override val metadata: MetadataQueries get() = PgMetadata
}
