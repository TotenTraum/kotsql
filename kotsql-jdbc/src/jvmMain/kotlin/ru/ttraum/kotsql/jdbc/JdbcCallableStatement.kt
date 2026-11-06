package ru.ttraum.kotsql.jdbc

import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.query
import kotlinx.coroutines.flow.first
import java.io.Reader
import java.math.BigDecimal
import java.sql.Blob
import java.sql.Clob
import java.sql.Date
import java.sql.NClob
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Time
import java.sql.Timestamp
import java.util.Calendar

/**
 * Runs a function or a procedure, and has what it returns ready for the getters. Parameters are addressed by position only.
 *
 * Calls that return their values as a result set stay as they are: with no parameter registered as an out parameter, the
 * result is there for [getResultSet]. With MySQL, whose out parameters take a further statement, any result sets of the
 * procedure are dropped if there are out parameters.
 */
internal class JdbcCallableStatement(
    conn: JdbcConnection,
    private val call: ParsedCall,
    type: Int,
    concurrency: Int,
) : JdbcPreparedStatement(conn, "", countParameters(call), null, type, concurrency), CallableDefaults {
    private val outParameters = java.util.TreeSet<Int>()
    private var values: ResultSet? = null
    private var lastRead: ResultSet? = null

    private val isProcedure: Boolean by lazy {
        if (call.returnsValue) return@lazy false
        val lookup = conn.dialect.calls.procedureLookupSql ?: return@lazy true
        val bareName = bareName(call.name, conn)
        conn.run { (query(lookup, listOf(bareName)).first()[0] as Number).toLong() > 0 }
    }

    override fun registerOutParameter(p0: Int, p1: Int) {
        checkOpen()
        checkIndex(p0)
        outParameters += p0
    }
    override fun registerOutParameter(p0: Int, p1: Int, p2: Int) = registerOutParameter(p0, p1)
    override fun registerOutParameter(p0: Int, p1: Int, p2: String?) = registerOutParameter(p0, p1)

    private fun checkIndex(index: Int) {
        if (index !in 1..parameterCount) throw SQLException("Parameter index $index is out of range 1..$parameterCount", "07009")
    }

    override fun execute(): Boolean {
        checkOpen()
        values = null
        if (call.returnsValue && 1 !in outParameters) throw SQLException("A call's return value has to be registered as parameter 1", "07004")
        val dialect = conn.dialect
        val firstArgument = if (call.returnsValue) 2 else 1
        val sent = ArrayList<Any?>()
        val before = ArrayList<Pair<String, Any?>>()
        val variables = ArrayList<String>()
        var index = firstArgument
        var keptOut = 0
        val arguments = ArrayList<String>()
        for (argument in call.arguments) {
            if (argument != "?") {
                // Any `?` in an expression is an input, in place.
                val count = argument.count { it == '?' }
                var text = argument
                repeat(count) {
                    text = text.replaceFirst("?", dialect.parameterMarker(sent.size + 1))
                    sent += boundValue(index++)
                }
                arguments += text
                continue
            }
            val out = index in outParameters
            val input = isBound(index)
            when {
                !out -> {
                    arguments += dialect.parameterMarker(sent.size + 1)
                    sent += boundValue(index)
                }
                dialect.calls.outVariables -> {
                    val variable = "@kotsql_out_${++keptOut}"
                    variables += variable
                    arguments += variable
                    // An inout parameter's starting value goes in first, in a statement of its own.
                    if (input) before += variable to boundValue(index)
                }
                !isProcedure -> if (input) throw SQLException("Parameter $index of a function can't be both, here", "0A000") // an OUT argument isn't one that a function call takes
                input -> {
                    arguments += dialect.parameterMarker(sent.size + 1)
                    sent += boundValue(index)
                }
                else -> arguments += "NULL"
            }
            index++
        }
        for ((variable, value) in before) runCall("SET $variable = ${dialect.parameterMarker(1)}", listOf(value))
        val sql = if (isProcedure) "CALL ${call.name}(${arguments.joinToString(", ")})" else dialect.calls.functionSql(call.name, arguments)
        val hasResult = runCall(sql, sent)
        if (outParameters.isEmpty()) return hasResult
        if (variables.isNotEmpty()) {
            while (getMoreResults() || getLargeUpdateCount() != -1L) { /* the procedure's own results */ }
            runCall("SELECT " + variables.joinToString(", "), emptyList())
        }
        val row = takeResultSet() ?: throw SQLException("The call returned nothing for its out parameters", "02000")
        if (!row.next()) throw SQLException("The call returned nothing for its out parameters", "02000")
        values = row
        return false
    }

    override fun executeQuery(): ResultSet {
        if (execute()) return resultSet!!
        throw SQLException("The call did not return a result set", "02000")
    }

    override fun addBatch(): Unit = unsupported("A batch of calls")
    override fun executeLargeBatch(): LongArray = unsupported("A batch of calls")

    private fun value(index: Int): ResultSet {
        checkOpen()
        checkIndex(index)
        if (index !in outParameters) throw SQLException("Parameter $index isn't registered as an out parameter", "07009")
        return (values ?: throw SQLException("The statement hasn't been executed", "HY010")).also { lastRead = it }
    }
    private fun column(index: Int) = outParameters.headSet(index).size + 1

    override fun wasNull(): Boolean = lastRead?.wasNull() ?: throw SQLException("No out parameter has been read", "HY010")
    override fun getString(p0: Int): String? = value(p0).getString(column(p0))
    override fun getNString(p0: Int): String? = getString(p0)
    override fun getBoolean(p0: Int): Boolean = value(p0).getBoolean(column(p0))
    override fun getByte(p0: Int): Byte = value(p0).getByte(column(p0))
    override fun getShort(p0: Int): Short = value(p0).getShort(column(p0))
    override fun getInt(p0: Int): Int = value(p0).getInt(column(p0))
    override fun getLong(p0: Int): Long = value(p0).getLong(column(p0))
    override fun getFloat(p0: Int): Float = value(p0).getFloat(column(p0))
    override fun getDouble(p0: Int): Double = value(p0).getDouble(column(p0))
    override fun getBigDecimal(p0: Int): BigDecimal? = value(p0).getBigDecimal(column(p0))
    @Deprecated("Deprecated in JDBC") @Suppress("DEPRECATION")
    override fun getBigDecimal(p0: Int, p1: Int): BigDecimal? = value(p0).getBigDecimal(column(p0), p1)
    override fun getBytes(p0: Int): ByteArray? = value(p0).getBytes(column(p0))
    override fun getDate(p0: Int): Date? = value(p0).getDate(column(p0))
    override fun getDate(p0: Int, p1: Calendar?): Date? = value(p0).getDate(column(p0), p1)
    override fun getTime(p0: Int): Time? = value(p0).getTime(column(p0))
    override fun getTime(p0: Int, p1: Calendar?): Time? = value(p0).getTime(column(p0), p1)
    override fun getTimestamp(p0: Int): Timestamp? = value(p0).getTimestamp(column(p0))
    override fun getTimestamp(p0: Int, p1: Calendar?): Timestamp? = value(p0).getTimestamp(column(p0), p1)
    override fun getObject(p0: Int): Any? = value(p0).getObject(column(p0))
    override fun getObject(p0: Int, p1: MutableMap<String, Class<*>>?): Any? = value(p0).getObject(column(p0), p1)
    override fun <T> getObject(p0: Int, p1: Class<T>?): T = value(p0).getObject(column(p0), p1)
    override fun getArray(p0: Int): java.sql.Array? = value(p0).getArray(column(p0))
    override fun getBlob(p0: Int): Blob? = value(p0).getBlob(column(p0))
    override fun getClob(p0: Int): Clob? = value(p0).getClob(column(p0))
    override fun getNClob(p0: Int): NClob? = value(p0).getNClob(column(p0))
    override fun getCharacterStream(p0: Int): Reader? = value(p0).getCharacterStream(column(p0))
    override fun getNCharacterStream(p0: Int): Reader? = getCharacterStream(p0)
    override fun getURL(p0: Int): java.net.URL? = value(p0).getURL(column(p0))
}

private fun countParameters(call: ParsedCall) = (if (call.returnsValue) 1 else 0) + call.arguments.sumOf { a -> a.count { it == '?' } }

/** How the catalog has the name of a function or a procedure. */
private fun bareName(name: String, conn: JdbcConnection): String {
    val last = name.substringAfterLast('.').trim()
    val quote = conn.dialect.identifierQuote
    if (last.startsWith(quote)) return last.removeSurrounding(quote)
    return when (conn.dialect.identifierCase) {
        ru.ttraum.kotsql.IdentifierCase.UPPER -> last.uppercase()
        ru.ttraum.kotsql.IdentifierCase.LOWER -> last.lowercase()
        else -> last
    }
}
