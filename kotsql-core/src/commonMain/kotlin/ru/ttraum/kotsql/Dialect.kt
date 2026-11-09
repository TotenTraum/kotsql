package ru.ttraum.kotsql

public enum class IdentifierCase {
    /** Folded to upper case, as the SQL standard says. */
    UPPER,
    LOWER,

    /** Stored as they were written. */
    AS_WRITTEN,
}

/** How the names of a table can be qualified: what comes before the dot. */
public enum class TableQualifier { SCHEMA, CATALOG, NONE }

public enum class BackslashEscapes {
    NEVER,

    /** In strings of either quote, but not in a backtick's identifier. */
    IN_STRINGS,

    /** Only in a string with an `E` in front of it. */
    WITH_E_PREFIX,
}

/**
 * Where text in a statement isn't SQL: its quotes and comments, in which a marker, a brace or a semicolon means nothing.
 * A database's syntax is stated here, for whoever needs to read a statement to tell, rather than concluded from anything else.
 *
 * @param quotes what opens a quoted string or identifier, which the same character closes and, when doubled, stands for itself
 * @param dashCommentNeedsSpace `--` starts a comment only with whitespace after it, so that `5--1` is an expression
 */
public class SqlSyntax(
    public val quotes: String = "'\"",
    public val bracketIdentifiers: Boolean = false,
    public val backslashEscapes: BackslashEscapes = BackslashEscapes.NEVER,
    public val dollarQuotedStrings: Boolean = false,
    public val nestedComments: Boolean = false,
    public val hashComments: Boolean = false,
    public val dashCommentNeedsSpace: Boolean = false,
) {
    /** The index after the string, quoted identifier or comment that starts at [start], or [start] if none does. */
    public fun opaqueEnd(sql: String, start: Int): Int {
        val n = sql.length
        val c = sql[start]
        return when {
            c == '-' -> {
                if (!sql.startsWith("--", start)) return start
                if (dashCommentNeedsSpace && start + 2 < n && !sql[start + 2].isWhitespace()) return start
                lineEnd(sql, start)
            }
            c == '/' -> if (sql.startsWith("/*", start)) blockCommentEnd(sql, start) else start
            c == '#' -> if (hashComments) lineEnd(sql, start) else start
            c == '[' -> if (bracketIdentifiers) sql.indexOf(']', start).let { if (it < 0) n else it + 1 } else start
            c == '$' -> if (dollarQuotedStrings) dollarQuoteEnd(sql, start) else start
            c == '\'' || c == '"' || c == '`' -> if (c in quotes) quotedEnd(sql, start, hasBackslashEscapes(sql, start)) else start
            else -> start
        }
    }

    private fun hasBackslashEscapes(sql: String, start: Int) = when (backslashEscapes) {
        BackslashEscapes.NEVER -> false
        BackslashEscapes.IN_STRINGS -> sql[start] != '`'
        BackslashEscapes.WITH_E_PREFIX -> sql[start] == '\'' && start > 0 && (sql[start - 1] == 'E' || sql[start - 1] == 'e') &&
            (start < 2 || !isIdentifierPart(sql[start - 2]))
    }

    private fun quotedEnd(sql: String, start: Int, escapes: Boolean): Int {
        val n = sql.length
        val quote = sql[start]
        var j = start + 1
        while (j < n) when {
            escapes && sql[j] == '\\' -> j += 2
            sql[j] != quote -> j++
            j + 1 < n && sql[j + 1] == quote -> j += 2
            else -> return j + 1
        }
        return n
    }

    private fun lineEnd(sql: String, start: Int) = sql.indexOf('\n', start).let { if (it < 0) sql.length else it + 1 }

    private fun blockCommentEnd(sql: String, start: Int): Int {
        if (!nestedComments) return sql.indexOf("*/", start + 2).let { if (it < 0) sql.length else it + 2 }
        var depth = 1
        var j = start + 2
        while (j < sql.length && depth > 0) when {
            sql.startsWith("/*", j) -> { depth++; j += 2 }
            sql.startsWith("*/", j) -> { depth--; j += 2 }
            else -> j++
        }
        return j
    }

    private fun dollarQuoteEnd(sql: String, start: Int): Int {
        val n = sql.length
        if (start > 0 && isIdentifierPart(sql[start - 1])) return start
        var j = start + 1
        if (j < n && sql[j].isDigit()) return start // a parameter like $1
        while (j < n && sql[j] != '$' && isIdentifierPart(sql[j])) j++
        if (j >= n || sql[j] != '$') return start
        val tag = sql.substring(start, j + 1)
        val close = sql.indexOf(tag, j + 1)
        return if (close < 0) n else close + tag.length
    }

    private fun isIdentifierPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}

/**
 * How functions and procedures get called.
 *
 * @param procedures whether the database has any
 * @param procedureLookupSql counts the procedures of the name in its parameter, for a database where a call without a return
 *   value may be either. Null if such a call is always of a procedure.
 * @param outVariables a procedure's out parameters take session variables, rather than coming back as a row
 * @param tableFunctions a function's result is read as a table, with its out parameters as columns
 */
public class CallSyntax(
    public val procedures: Boolean = true,
    public val procedureLookupSql: String? = null,
    public val outVariables: Boolean = false,
    public val tableFunctions: Boolean = false,
) {
    /** The statement that calls a function with these arguments. */
    public fun functionSql(name: String, arguments: List<String>): String {
        val call = "$name(${arguments.joinToString(", ")})"
        return if (tableFunctions) "SELECT * FROM $call" else "SELECT $call"
    }
}

/** SQL with its positional `?` markers replaced by native ones. */
public data class TranslatedSql(val sql: String, val parameterCount: Int)

/** A query answering a metadata question. It returns the columns named by the matching JDBC `DatabaseMetaData` method. */
public data class MetadataQuery(val sql: String, val params: List<Any?> = emptyList())

/** Queries behind `DatabaseMetaData`. A null result means the database has no such concept: an empty result is reported. */
public interface MetadataQueries {
    public fun catalogs(): MetadataQuery? = null
    public fun schemas(catalog: String?, schemaPattern: String?): MetadataQuery? = null
    public fun tableTypes(): MetadataQuery? = null
    public fun tables(catalog: String?, schemaPattern: String?, tablePattern: String?, types: List<String>?): MetadataQuery? = null
    public fun columns(catalog: String?, schemaPattern: String?, tablePattern: String?, columnPattern: String?): MetadataQuery? = null
    public fun primaryKeys(catalog: String?, schema: String?, table: String): MetadataQuery? = null
    public fun importedKeys(catalog: String?, schema: String?, table: String): MetadataQuery? = null
    public fun exportedKeys(catalog: String?, schema: String?, table: String): MetadataQuery? = null
    public fun indexInfo(catalog: String?, schema: String?, table: String, unique: Boolean): MetadataQuery? = null
    public fun typeInfo(): MetadataQuery? = null
}

private val STANDARD_SYNTAX = SqlSyntax()
private val STANDARD_CALLS = CallSyntax()

/**
 * Everything the JDBC and R2DBC adapters need to know about a database's SQL. Defaults follow the SQL standard, so a
 * new driver overrides only what differs.
 */
public interface SqlDialect {
    public val productName: String

    /** Native parameter marker for a 1-based [index]. */
    public fun parameterMarker(index: Int): String

    /** Rewrites JDBC `?` markers to native ones. */
    public fun translatePositional(sql: String): TranslatedSql

    /** Number of parameters in SQL that uses native markers. */
    public fun parameterCount(sql: String): Int

    /** The text accepted by [parameterMarker]-based lookups, e.g. `$1` to index 0; null if [name] is not a marker. */
    public fun parameterIndex(name: String): Int?

    /**
     * The statement to run for generated values to be available, or null if this statement has none. It either returns
     * them as rows, with a clause added, or stays as it is if the driver reports them in [SqlResult.UpdateCount.generatedKeys].
     */
    public fun withGeneratedKeys(sql: String, columns: List<String>): String? = null

    /** A JDBC escape function in this database's SQL. Null if it's the same here, as most are. */
    public fun escapeFunction(name: String, arguments: List<String>): String? = null

    /** A date, time or timestamp literal of JDBC's escapes, by the type's keyword and the quoted text. */
    public fun temporalLiteral(keyword: String, text: String): String = "$keyword $text"

    public val syntax: SqlSyntax get() = STANDARD_SYNTAX
    public val tableQualifier: TableQualifier get() = TableQualifier.SCHEMA
    public val calls: CallSyntax get() = STANDARD_CALLS

    public val metadata: MetadataQueries get() = object : MetadataQueries {}
    public val identifierQuote: String get() = "\""
    public val searchStringEscape: String get() = "\\"
    public val defaultIsolation: IsolationLevel get() = IsolationLevel.READ_COMMITTED

    /** How unquoted identifiers are stored, which tools rely on when looking tables up in the metadata. */
    public val identifierCase: IdentifierCase get() = IdentifierCase.UPPER

    public fun quoteIdentifier(name: String): String = identifierQuote + name.replace(identifierQuote, identifierQuote + identifierQuote) + identifierQuote

    /** The statements that start a transaction. More than one where a database can't say it all in one. */
    public fun beginStatements(isolation: IsolationLevel? = null, readOnly: Boolean? = null): List<String> = listOf(
        buildString {
            append("START TRANSACTION")
            isolation?.let { append(" ISOLATION LEVEL ").append(it.sql) }
            readOnly?.let { append(if (it) " READ ONLY" else " READ WRITE") }
        },
    )
    public fun commitSql(): String = "COMMIT"
    public fun rollbackSql(): String = "ROLLBACK"
    public fun savepointSql(name: String): String = "SAVEPOINT ${quoteIdentifier(name)}"
    public fun releaseSavepointSql(name: String): String = "RELEASE SAVEPOINT ${quoteIdentifier(name)}"
    public fun rollbackToSavepointSql(name: String): String = "ROLLBACK TO SAVEPOINT ${quoteIdentifier(name)}"

    /** Applies to transactions started later in this session. */
    public fun setIsolationSql(level: IsolationLevel): String
    public fun showIsolationSql(): String
    public fun setReadOnlySql(readOnly: Boolean): String
    public fun setStatementTimeoutSql(millis: Long): String?  = null
    public fun setLockTimeoutSql(millis: Long): String? = null
    public fun setSchemaSql(schema: String): String? = null
    public fun currentSchemaSql(): String? = null
    public fun currentCatalogSql(): String? = null

    /** Null where a connection can't change catalogs. */
    public fun setCatalogSql(catalog: String): String? = null
}
