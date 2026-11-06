package ru.ttraum.kotsql.spi

import ru.ttraum.kotsql.ColumnInfo
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.SqlRow
import ru.ttraum.kotsql.SqlType

/** A row of values in memory. */
class ValuesRow(override val columns: List<ColumnInfo>, private val values: List<Any?>) : SqlRow {
    override fun get(index: Int): Any? {
        if (index !in values.indices) throw IndexOutOfBoundsException("Column index $index, row has ${values.size} columns")
        return values[index]
    }
    override fun getText(index: Int): String? = get(index)?.toString()
}

/** A result set in memory. */
class ListRows(override val columns: List<ColumnInfo>, rows: List<SqlRow>) : SqlResult.Rows {
    private val iterator = rows.iterator()
    override val updateCount: Long? = null
    override suspend fun next(): SqlRow? = nextBuffered()
    override fun nextBuffered(): SqlRow? = if (iterator.hasNext()) iterator.next() else null
}

private val GENERATED_KEY = listOf(ColumnInfo("GENERATED_KEY", SqlType.BIGINT, "BIGINT"))

/** Generated keys reported by [SqlResult.UpdateCount], as rows. */
fun generatedKeyRows(keys: List<Long>): ListRows = ListRows(GENERATED_KEY, keys.map { ValuesRow(GENERATED_KEY, listOf(it)) })
