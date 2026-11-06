package dev.ksql.mysql

import dev.ksql.IdentifierCase
import dev.ksql.IsolationLevel
import dev.ksql.MetadataQueries
import dev.ksql.MetadataQuery
import dev.ksql.SqlDialect
import dev.ksql.TranslatedSql

internal object MysqlDialect : SqlDialect {
    override val productName = "MySQL"
    override val identifierQuote = "`"
    override val identifierCase get() = IdentifierCase.AS_WRITTEN
    override val defaultIsolation = IsolationLevel.REPEATABLE_READ

    override fun parameterMarker(index: Int) = "?"

    /** MySQL's markers are the same as JDBC's, so this only counts them. */
    override fun translatePositional(sql: String) = TranslatedSql(sql, parameterCount(sql))

    override fun parameterCount(sql: String): Int {
        var count = 0
        var i = 0
        while (i < sql.length) {
            val end = opaqueEnd(sql, i)
            if (end > i) {
                i = end
            } else {
                if (sql[i] == '?') count++
                i++
            }
        }
        return count
    }

    /** `?1` is parameter 0. The markers themselves have no names, so this is what R2DBC's named binding can use. */
    override fun parameterIndex(name: String): Int? =
        if (name.startsWith("?")) name.substring(1).toIntOrNull()?.takeIf { it >= 1 }?.minus(1) else null

    /** End of the string, quoted identifier or comment starting at [start], or [start] if there is none. */
    private fun opaqueEnd(sql: String, start: Int): Int {
        val n = sql.length
        when (val c = sql[start]) {
            '\'', '"', '`' -> {
                var j = start + 1
                while (j < n) {
                    when {
                        c != '`' && sql[j] == '\\' -> j += 2
                        sql[j] != c -> j++
                        j + 1 < n && sql[j + 1] == c -> j += 2
                        else -> return j + 1
                    }
                }
                return n
            }
            '#' -> return lineEnd(sql, start)
            // MySQL requires whitespace after `--`, otherwise `5--1` would be a comment.
            '-' -> return if (sql.startsWith("--", start) && (start + 2 == n || sql[start + 2].isWhitespace())) lineEnd(sql, start) else start
            '/' -> {
                if (!sql.startsWith("/*", start)) return start
                val close = sql.indexOf("*/", start + 2)
                return if (close < 0) n else close + 2
            }
            else -> return start
        }
    }

    private fun lineEnd(sql: String, start: Int): Int = sql.indexOf('\n', start).let { if (it < 0) sql.length else it + 1 }

    /** Generated values come back in the OK packet, so the statement needs no change. */
    override fun withGeneratedKeys(sql: String, columns: List<String>): String? {
        val first = sql.trimStart().takeWhile { it.isLetter() }.uppercase()
        return if (first == "INSERT" || first == "REPLACE" || first == "UPDATE") sql else null
    }

    /** START TRANSACTION can't name an isolation level, which takes a statement of its own. */
    override fun beginStatements(isolation: IsolationLevel?, readOnly: Boolean?): List<String> = buildList {
        isolation?.let { add("SET TRANSACTION ISOLATION LEVEL ${it.sql}") }
        add(
            when (readOnly) {
                true -> "START TRANSACTION READ ONLY"
                false -> "START TRANSACTION READ WRITE"
                null -> "START TRANSACTION"
            },
        )
    }

    override fun setIsolationSql(level: IsolationLevel) = "SET SESSION TRANSACTION ISOLATION LEVEL ${level.sql}"
    override fun showIsolationSql() = "SELECT REPLACE(@@transaction_isolation, '-', ' ')"
    override fun setReadOnlySql(readOnly: Boolean) = "SET SESSION TRANSACTION ${if (readOnly) "READ ONLY" else "READ WRITE"}"
    override fun setStatementTimeoutSql(millis: Long) = "SET SESSION max_execution_time = $millis"
    override fun setLockTimeoutSql(millis: Long) = "SET SESSION innodb_lock_wait_timeout = ${(millis / 1000).coerceAtLeast(1)}"
    override fun currentCatalogSql() = "SELECT DATABASE()"
    override fun setCatalogSql(catalog: String) = "USE ${quoteIdentifier(catalog)}"
    override val metadata: MetadataQueries get() = MysqlMetadata
}

/** `DATA_TYPE` values are the constants of `java.sql.Types`, which the JDBC specification fixes. MySQL databases are catalogs. */
private const val JDBC_TYPE = """CASE c.DATA_TYPE
    WHEN 'tinyint' THEN CASE WHEN c.COLUMN_TYPE = 'tinyint(1)' THEN 16 ELSE -6 END WHEN 'smallint' THEN 5 WHEN 'mediumint' THEN 4
    WHEN 'int' THEN 4 WHEN 'bigint' THEN -5 WHEN 'float' THEN 7 WHEN 'double' THEN 8 WHEN 'decimal' THEN 3 WHEN 'char' THEN 1
    WHEN 'varchar' THEN 12 WHEN 'text' THEN -1 WHEN 'tinytext' THEN 12 WHEN 'mediumtext' THEN -1 WHEN 'longtext' THEN -1
    WHEN 'binary' THEN -2 WHEN 'varbinary' THEN -3 WHEN 'blob' THEN -4 WHEN 'tinyblob' THEN -3 WHEN 'mediumblob' THEN -4
    WHEN 'longblob' THEN -4 WHEN 'date' THEN 91 WHEN 'time' THEN 92 WHEN 'datetime' THEN 93 WHEN 'timestamp' THEN 93
    WHEN 'bit' THEN -7 WHEN 'year' THEN 91 WHEN 'json' THEN -1 WHEN 'enum' THEN 1 WHEN 'set' THEN 1 ELSE 1111 END"""

internal object MysqlMetadata : MetadataQueries {
    override fun catalogs() = MetadataQuery("SELECT SCHEMA_NAME AS TABLE_CAT FROM information_schema.SCHEMATA ORDER BY 1")

    override fun tableTypes() = MetadataQuery("SELECT 'TABLE' AS TABLE_TYPE UNION ALL SELECT 'VIEW' UNION ALL SELECT 'SYSTEM VIEW'")

    private fun current(catalog: String?) = catalog

    override fun tables(catalog: String?, schemaPattern: String?, tablePattern: String?, types: List<String>?): MetadataQuery {
        val wanted = (types ?: listOf("TABLE", "VIEW")).map { if (it == "TABLE") "BASE TABLE" else it }
        return MetadataQuery(
            """SELECT TABLE_SCHEMA AS TABLE_CAT, NULL AS TABLE_SCHEM, TABLE_NAME,
                 CASE TABLE_TYPE WHEN 'BASE TABLE' THEN 'TABLE' ELSE TABLE_TYPE END AS TABLE_TYPE,
                 TABLE_COMMENT AS REMARKS, NULL AS TYPE_CAT, NULL AS TYPE_SCHEM, NULL AS TYPE_NAME,
                 NULL AS SELF_REFERENCING_COL_NAME, NULL AS REF_GENERATION
               FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND (? IS NULL OR TABLE_NAME LIKE ?)
                 AND TABLE_TYPE IN (${wanted.joinToString(",") { "?" }})
               ORDER BY 4, 1, 3""",
            listOf(current(catalog), tablePattern, tablePattern) + wanted,
        )
    }

    override fun columns(catalog: String?, schemaPattern: String?, tablePattern: String?, columnPattern: String?) = MetadataQuery(
        """SELECT c.TABLE_SCHEMA AS TABLE_CAT, NULL AS TABLE_SCHEM, c.TABLE_NAME, c.COLUMN_NAME, $JDBC_TYPE AS DATA_TYPE,
             UPPER(c.DATA_TYPE) AS TYPE_NAME,
             CAST(COALESCE(c.CHARACTER_MAXIMUM_LENGTH, c.NUMERIC_PRECISION, c.DATETIME_PRECISION, 0) AS SIGNED) AS COLUMN_SIZE,
             NULL AS BUFFER_LENGTH, CAST(c.NUMERIC_SCALE AS SIGNED) AS DECIMAL_DIGITS, 10 AS NUM_PREC_RADIX,
             CASE c.IS_NULLABLE WHEN 'NO' THEN 0 ELSE 1 END AS NULLABLE, c.COLUMN_COMMENT AS REMARKS,
             c.COLUMN_DEFAULT AS COLUMN_DEF, NULL AS SQL_DATA_TYPE, NULL AS SQL_DATETIME_SUB,
             CAST(c.CHARACTER_OCTET_LENGTH AS SIGNED) AS CHAR_OCTET_LENGTH, c.ORDINAL_POSITION, c.IS_NULLABLE,
             NULL AS SCOPE_CATALOG, NULL AS SCOPE_SCHEMA, NULL AS SCOPE_TABLE, NULL AS SOURCE_DATA_TYPE,
             CASE WHEN c.EXTRA LIKE '%auto_increment%' THEN 'YES' ELSE 'NO' END AS IS_AUTOINCREMENT,
             CASE WHEN c.EXTRA LIKE '%GENERATED%' THEN 'YES' ELSE 'NO' END AS IS_GENERATEDCOLUMN
           FROM information_schema.COLUMNS c
           WHERE c.TABLE_SCHEMA = COALESCE(?, DATABASE()) AND (? IS NULL OR c.TABLE_NAME LIKE ?) AND (? IS NULL OR c.COLUMN_NAME LIKE ?)
           ORDER BY 1, 3, c.ORDINAL_POSITION""",
        listOf(catalog, tablePattern, tablePattern, columnPattern, columnPattern),
    )

    override fun primaryKeys(catalog: String?, schema: String?, table: String) = MetadataQuery(
        """SELECT TABLE_SCHEMA AS TABLE_CAT, NULL AS TABLE_SCHEM, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION AS KEY_SEQ, 'PRIMARY' AS PK_NAME
           FROM information_schema.KEY_COLUMN_USAGE
           WHERE CONSTRAINT_NAME = 'PRIMARY' AND TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ?
           ORDER BY 4""",
        listOf(catalog, table),
    )

    private fun foreignKeys(filter: String, catalog: String?, table: String) = MetadataQuery(
        """SELECT k.REFERENCED_TABLE_SCHEMA AS PKTABLE_CAT, NULL AS PKTABLE_SCHEM, k.REFERENCED_TABLE_NAME AS PKTABLE_NAME,
             k.REFERENCED_COLUMN_NAME AS PKCOLUMN_NAME, k.TABLE_SCHEMA AS FKTABLE_CAT, NULL AS FKTABLE_SCHEM, k.TABLE_NAME AS FKTABLE_NAME,
             k.COLUMN_NAME AS FKCOLUMN_NAME, k.ORDINAL_POSITION AS KEY_SEQ,
             CASE r.UPDATE_RULE WHEN 'CASCADE' THEN 0 WHEN 'SET NULL' THEN 2 WHEN 'SET DEFAULT' THEN 4 WHEN 'RESTRICT' THEN 1 ELSE 3 END AS UPDATE_RULE,
             CASE r.DELETE_RULE WHEN 'CASCADE' THEN 0 WHEN 'SET NULL' THEN 2 WHEN 'SET DEFAULT' THEN 4 WHEN 'RESTRICT' THEN 1 ELSE 3 END AS DELETE_RULE,
             k.CONSTRAINT_NAME AS FK_NAME, NULL AS PK_NAME, 7 AS DEFERRABILITY
           FROM information_schema.KEY_COLUMN_USAGE k
           JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME
           WHERE k.REFERENCED_TABLE_NAME IS NOT NULL AND $filter
           ORDER BY 1, 3, 9""",
        listOf(catalog, table),
    )

    override fun importedKeys(catalog: String?, schema: String?, table: String) =
        foreignKeys("k.TABLE_SCHEMA = COALESCE(?, DATABASE()) AND k.TABLE_NAME = ?", catalog, table)

    override fun exportedKeys(catalog: String?, schema: String?, table: String) =
        foreignKeys("k.REFERENCED_TABLE_SCHEMA = COALESCE(?, DATABASE()) AND k.REFERENCED_TABLE_NAME = ?", catalog, table)

    override fun indexInfo(catalog: String?, schema: String?, table: String, unique: Boolean) = MetadataQuery(
        """SELECT TABLE_SCHEMA AS TABLE_CAT, NULL AS TABLE_SCHEM, TABLE_NAME, NON_UNIQUE <> 0 AS NON_UNIQUE, NULL AS INDEX_QUALIFIER,
             INDEX_NAME, 3 AS TYPE, SEQ_IN_INDEX AS ORDINAL_POSITION, COLUMN_NAME, COLLATION AS ASC_OR_DESC,
             CARDINALITY, 0 AS PAGES, NULL AS FILTER_CONDITION
           FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ? AND (? = 0 OR NON_UNIQUE = 0)
           ORDER BY 4, 6, 8""",
        listOf(catalog, table, if (unique) 1 else 0),
    )
}
