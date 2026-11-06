package ru.ttraum.kotsql.jdbc

import ru.ttraum.kotsql.ColumnInfo
import ru.ttraum.kotsql.SqlRow
import java.sql.SQLException

/** What an updatable result set needs of the statement behind it: the one table that its rows come from. */
internal class UpdatableTarget(val schema: String?, val table: String, val quotedName: String) {
    companion object {
        private val SINGLE_TABLE = Regex(
            """^\s*select\s+.+?\s+from\s+((?:[\w$]+|"[^"]+"|`[^`]+`)(?:\.(?:[\w$]+|"[^"]+"|`[^`]+`))?)\s*(?:\s(?:as\s+)?(?!where\b|order\b|limit\b|offset\b|for\b|fetch\b)[\w$]+\s*)?(?:\s(?:where|order|limit|offset|for|fetch)\b.*)?;?\s*$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val NOT_ONE_TABLE = Regex("""\b(join|union|intersect|except|group\s+by|having|distinct)\b""", RegexOption.IGNORE_CASE)

        /** Null if the statement isn't a plain query of one table: only that can tell which row of what to change. */
        fun of(sql: String, conn: JdbcConnection): UpdatableTarget? {
            if (NOT_ONE_TABLE.containsMatchIn(sql)) return null
            val name = SINGLE_TABLE.find(sql)?.groupValues?.get(1) ?: return null
            val dialect = conn.dialect
            val quote = dialect.identifierQuote
            fun stored(part: String) = when {
                part.startsWith(quote) -> part.removeSurrounding(quote)
                dialect.identifierCase == ru.ttraum.kotsql.IdentifierCase.UPPER -> part.uppercase()
                dialect.identifierCase == ru.ttraum.kotsql.IdentifierCase.LOWER -> part.lowercase()
                else -> part
            }
            val parts = name.split('.')
            return UpdatableTarget(parts.getOrNull(parts.size - 2)?.let(::stored), stored(parts.last()), name)
        }
    }
}

/** A row with some of its columns changed, or all of them for a row being inserted. */
internal class OverlayRow(
    override val columns: List<ColumnInfo>,
    private val base: SqlRow?,
    private val changes: Map<Int, Any?>,
) : SqlRow {
    override val codecs get() = base?.codecs ?: super.codecs
    override fun get(index: Int): Any? = if (index + 1 in changes) changes[index + 1] else base?.get(index)
    override fun getText(index: Int): String? = if (index + 1 in changes) changes[index + 1]?.toString() else base?.getText(index)
}

internal fun notUpdatable(reason: String): Nothing = throw SQLException("The result set is not updatable: $reason", "0A000")
