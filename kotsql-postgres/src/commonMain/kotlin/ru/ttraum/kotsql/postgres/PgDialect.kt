package ru.ttraum.kotsql.postgres

import ru.ttraum.kotsql.BackslashEscapes
import ru.ttraum.kotsql.CallSyntax
import ru.ttraum.kotsql.IdentifierCase
import ru.ttraum.kotsql.IsolationLevel
import ru.ttraum.kotsql.MetadataQueries
import ru.ttraum.kotsql.SqlDialect
import ru.ttraum.kotsql.SqlSyntax
import ru.ttraum.kotsql.TranslatedSql

internal object PgDialect : SqlDialect {
    override val productName = "PostgreSQL"
    override val identifierCase get() = IdentifierCase.LOWER

    override val syntax = SqlSyntax(backslashEscapes = BackslashEscapes.WITH_E_PREFIX, dollarQuotedStrings = true, nestedComments = true)
    override val calls = CallSyntax(
        procedureLookupSql = "SELECT count(*) FROM pg_catalog.pg_proc WHERE prokind = 'p' AND proname = $1",
        tableFunctions = true,
    )

    /** What PostgreSQL has under another name or form. The other functions of the JDBC specification are the same here. */
    override fun escapeFunction(name: String, arguments: List<String>): String? {
        val a = arguments
        fun extract(field: String) = "EXTRACT($field FROM ${a[0]})"
        return when (name.uppercase() to a.size) {
            "UCASE" to 1 -> "UPPER(${a[0]})"
            "LCASE" to 1 -> "LOWER(${a[0]})"
            "IFNULL" to 2 -> "COALESCE(${a[0]}, ${a[1]})"
            "LOCATE" to 2 -> "POSITION(${a[0]} IN ${a[1]})"
            "CURDATE" to 0 -> "CURRENT_DATE"
            "CURTIME" to 0 -> "CURRENT_TIME"
            "DATABASE" to 0 -> "CURRENT_DATABASE()"
            "USER" to 0 -> "CURRENT_USER"
            "CEILING" to 1 -> "CEIL(${a[0]})"
            "LOG" to 1 -> "LN(${a[0]})"
            "LOG10" to 1 -> "LOG(${a[0]})"
            "TRUNCATE" to 2 -> "TRUNC(${a[0]}, ${a[1]})"
            "YEAR" to 1 -> extract("YEAR")
            "MONTH" to 1 -> extract("MONTH")
            "DAYOFMONTH" to 1 -> extract("DAY")
            "DAYOFYEAR" to 1 -> extract("DOY")
            "DAYOFWEEK" to 1 -> "(${extract("DOW")} + 1)"
            "HOUR" to 1 -> extract("HOUR")
            "MINUTE" to 1 -> extract("MINUTE")
            "SECOND" to 1 -> "TRUNC(${extract("SECOND")})"
            "WEEK" to 1 -> extract("WEEK")
            "QUARTER" to 1 -> extract("QUARTER")
            else -> null
        }
    }
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
    private fun opaqueEnd(sql: String, start: Int) = syntax.opaqueEnd(sql, start)

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
