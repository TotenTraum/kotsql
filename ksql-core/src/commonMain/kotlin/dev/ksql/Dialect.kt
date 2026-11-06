package dev.ksql

enum class IdentifierCase {
    /** Folded to upper case, as the SQL standard says. */
    UPPER,
    LOWER,

    /** Stored as they were written. */
    AS_WRITTEN,
}

/** SQL with its positional `?` markers replaced by native ones. */
data class TranslatedSql(val sql: String, val parameterCount: Int)

/** A query answering a metadata question. It returns the columns named by the matching JDBC `DatabaseMetaData` method. */
data class MetadataQuery(val sql: String, val params: List<Any?> = emptyList())

/** Queries behind `DatabaseMetaData`. A null result means the database has no such concept: an empty result is reported. */
interface MetadataQueries {
    fun catalogs(): MetadataQuery? = null
    fun schemas(catalog: String?, schemaPattern: String?): MetadataQuery? = null
    fun tableTypes(): MetadataQuery? = null
    fun tables(catalog: String?, schemaPattern: String?, tablePattern: String?, types: List<String>?): MetadataQuery? = null
    fun columns(catalog: String?, schemaPattern: String?, tablePattern: String?, columnPattern: String?): MetadataQuery? = null
    fun primaryKeys(catalog: String?, schema: String?, table: String): MetadataQuery? = null
    fun importedKeys(catalog: String?, schema: String?, table: String): MetadataQuery? = null
    fun exportedKeys(catalog: String?, schema: String?, table: String): MetadataQuery? = null
    fun indexInfo(catalog: String?, schema: String?, table: String, unique: Boolean): MetadataQuery? = null
    fun typeInfo(): MetadataQuery? = null
}

/**
 * Everything the JDBC and R2DBC adapters need to know about a database's SQL. Defaults follow the SQL standard, so a
 * new driver overrides only what differs.
 */
interface SqlDialect {
    val productName: String

    /** Native parameter marker for a 1-based [index]. */
    fun parameterMarker(index: Int): String

    /** Rewrites JDBC `?` markers to native ones. */
    fun translatePositional(sql: String): TranslatedSql

    /** Number of parameters in SQL that uses native markers. */
    fun parameterCount(sql: String): Int

    /** The text accepted by [parameterMarker]-based lookups, e.g. `$1` to index 0; null if [name] is not a marker. */
    fun parameterIndex(name: String): Int?

    /**
     * The statement to run for generated values to be available, or null if this statement has none. It either returns
     * them as rows, with a clause added, or stays as it is if the driver reports them in [SqlResult.UpdateCount.generatedKeys].
     */
    fun withGeneratedKeys(sql: String, columns: List<String>): String? = null

    val metadata: MetadataQueries get() = object : MetadataQueries {}
    val identifierQuote: String get() = "\""
    val searchStringEscape: String get() = "\\"
    val defaultIsolation: IsolationLevel get() = IsolationLevel.READ_COMMITTED

    /** How unquoted identifiers are stored, which tools rely on when looking tables up in the metadata. */
    val identifierCase: IdentifierCase get() = IdentifierCase.UPPER

    fun quoteIdentifier(name: String): String = identifierQuote + name.replace(identifierQuote, identifierQuote + identifierQuote) + identifierQuote

    /** The statements that start a transaction. More than one where a database can't say it all in one. */
    fun beginStatements(isolation: IsolationLevel? = null, readOnly: Boolean? = null): List<String> = listOf(
        buildString {
            append("START TRANSACTION")
            isolation?.let { append(" ISOLATION LEVEL ").append(it.sql) }
            readOnly?.let { append(if (it) " READ ONLY" else " READ WRITE") }
        },
    )
    fun commitSql(): String = "COMMIT"
    fun rollbackSql(): String = "ROLLBACK"
    fun savepointSql(name: String): String = "SAVEPOINT ${quoteIdentifier(name)}"
    fun releaseSavepointSql(name: String): String = "RELEASE SAVEPOINT ${quoteIdentifier(name)}"
    fun rollbackToSavepointSql(name: String): String = "ROLLBACK TO SAVEPOINT ${quoteIdentifier(name)}"

    /** Applies to transactions started later in this session. */
    fun setIsolationSql(level: IsolationLevel): String
    fun showIsolationSql(): String
    fun setReadOnlySql(readOnly: Boolean): String
    fun setStatementTimeoutSql(millis: Long): String?  = null
    fun setLockTimeoutSql(millis: Long): String? = null
    fun setSchemaSql(schema: String): String? = null
    fun currentSchemaSql(): String? = null
    fun currentCatalogSql(): String? = null

    /** Null where a connection can't change catalogs. */
    fun setCatalogSql(catalog: String): String? = null
}
