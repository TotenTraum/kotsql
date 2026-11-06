package ru.ttraum.kotsql.sqlite

import ru.ttraum.kotsql.CallSyntax
import ru.ttraum.kotsql.IdentifierCase
import ru.ttraum.kotsql.IsolationLevel
import ru.ttraum.kotsql.MetadataQueries
import ru.ttraum.kotsql.MetadataQuery
import ru.ttraum.kotsql.SqlDialect
import ru.ttraum.kotsql.SqlSyntax
import ru.ttraum.kotsql.TableQualifier
import ru.ttraum.kotsql.TranslatedSql

internal object SqliteDialect : SqlDialect {
    override val productName = "SQLite"
    override val identifierCase get() = IdentifierCase.AS_WRITTEN
    override val defaultIsolation = IsolationLevel.SERIALIZABLE
    override val syntax = SqlSyntax(quotes = "'\"`", bracketIdentifiers = true)
    override val tableQualifier get() = TableQualifier.NONE
    override val calls = CallSyntax(procedures = false)

    override fun parameterMarker(index: Int) = "?"
    override fun translatePositional(sql: String) = TranslatedSql(sql, parameterCount(sql))

    /** As SQLite counts them: `?` is one more, `?5` makes it at least five, and a name counts once however often it's used. */
    override fun parameterCount(sql: String): Int {
        var count = 0
        val names = HashMap<String, Int>()
        var i = 0
        while (i < sql.length) {
            val end = opaqueEnd(sql, i)
            if (end > i) {
                i = end
                continue
            }
            val c = sql[i]
            when {
                c == '?' -> {
                    var j = i + 1
                    while (j < sql.length && sql[j].isDigit()) j++
                    count = if (j > i + 1) maxOf(count, sql.substring(i + 1, j).toInt()) else count + 1
                    i = j
                }
                (c == ':' || c == '@' || c == '$') && i + 1 < sql.length && (sql[i + 1].isLetterOrDigit() || sql[i + 1] == '_') -> {
                    var j = i + 1
                    while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_' || sql[j] == '$')) j++
                    names.getOrPut(sql.substring(i, j)) { ++count }
                    i = j
                }
                else -> i++
            }
        }
        return count
    }

    /** `?1` is parameter 0. */
    override fun parameterIndex(name: String): Int? =
        if (name.startsWith("?")) name.substring(1).toIntOrNull()?.takeIf { it >= 1 }?.minus(1) else null

    private fun opaqueEnd(sql: String, start: Int) = syntax.opaqueEnd(sql, start)

    /** The first keyword of a statement, past comments. */
    internal fun verb(sql: String): String {
        var i = 0
        while (i < sql.length) {
            val end = if (sql[i] == '\'' || sql[i] == '"') i else opaqueEnd(sql, i)
            when {
                end > i -> i = end
                sql[i].isWhitespace() -> i++
                else -> return sql.substring(i).takeWhile { it.isLetter() }.uppercase()
            }
        }
        return ""
    }

    /**
     * Splits a script at its semicolons, since a prepared statement only takes the first statement of what it's given and
     * would quietly drop the rest. A trigger's body has semicolons of its own, up to the `;END;` that SQLite looks for too.
     */
    internal fun splitStatements(sql: String): List<String> {
        val statements = ArrayList<String>()
        var from = 0
        var i = 0
        var lastSemicolon = -1
        while (i < sql.length) {
            val end = opaqueEnd(sql, i)
            if (end > i) {
                i = end
                continue
            }
            if (sql[i] == ';') {
                val statement = sql.substring(from, i)
                val endsTrigger = !isTrigger(statement) || (lastSemicolon >= from && sql.substring(lastSemicolon + 1, i).trim().equals("END", ignoreCase = true))
                if (endsTrigger) {
                    if (verb(statement).isNotEmpty()) statements += statement
                    from = i + 1
                }
                lastSemicolon = i
            }
            i++
        }
        val rest = sql.substring(from)
        if (verb(rest).isNotEmpty()) statements += rest
        return statements
    }

    private val TRIGGER = Regex("""^\s*CREATE\s+(TEMP\s+|TEMPORARY\s+)?TRIGGER\b""", RegexOption.IGNORE_CASE)
    private fun isTrigger(statement: String) = TRIGGER.containsMatchIn(statement)

    /** Dates and times are text here. */
    override fun temporalLiteral(keyword: String, text: String) = text

    /** What SQLite has under another name or form. */
    override fun escapeFunction(name: String, arguments: List<String>): String? {
        val a = arguments
        fun part(format: String) = "CAST(strftime('$format', ${a[0]}) AS INTEGER)"
        return when (name.uppercase() to a.size) {
            "UCASE" to 1 -> "UPPER(${a[0]})"
            "LCASE" to 1 -> "LOWER(${a[0]})"
            "LOCATE" to 2 -> "INSTR(${a[1]}, ${a[0]})"
            "CONCAT" to 2 -> "(${a[0]} || ${a[1]})"
            "CHAR_LENGTH" to 1 -> "LENGTH(${a[0]})"
            "SUBSTRING" to 3 -> "SUBSTR(${a[0]}, ${a[1]}, ${a[2]})"
            "NOW" to 0 -> "DATETIME('now')"
            "CURDATE" to 0 -> "DATE('now')"
            "CURTIME" to 0 -> "TIME('now')"
            "DATABASE" to 0 -> "'main'"
            "YEAR" to 1 -> part("%Y")
            "MONTH" to 1 -> part("%m")
            "DAYOFMONTH" to 1 -> part("%d")
            "HOUR" to 1 -> part("%H")
            "MINUTE" to 1 -> part("%M")
            "SECOND" to 1 -> part("%S")
            "DAYOFYEAR" to 1 -> part("%j")
            else -> null
        }
    }

    /** Generated values are asked for afterwards, so the statement needs no change. */
    override fun withGeneratedKeys(sql: String, columns: List<String>): String? {
        val first = verb(sql)
        return if (first == "INSERT" || first == "REPLACE") sql else null
    }

    /** Every transaction is serializable, whatever is asked for, and a read-only one is a setting of the connection. */
    override fun beginStatements(isolation: IsolationLevel?, readOnly: Boolean?): List<String> = listOf("BEGIN")
    override fun setIsolationSql(level: IsolationLevel) = "PRAGMA read_uncommitted = ${if (level == IsolationLevel.READ_UNCOMMITTED) 1 else 0}"
    override fun showIsolationSql() = "SELECT CASE read_uncommitted WHEN 1 THEN 'READ UNCOMMITTED' ELSE 'SERIALIZABLE' END FROM pragma_read_uncommitted"
    override fun setReadOnlySql(readOnly: Boolean) = "PRAGMA query_only = ${if (readOnly) 1 else 0}"
    override val metadata: MetadataQueries get() = SqliteMetadata
}

/** `DATA_TYPE` as `java.sql.Types` has it, by the affinity rules of SQLite, as its columns may be declared as anything. */
private const val JDBC_TYPE = """CASE
    WHEN upper(c.type) LIKE '%INT%' THEN 4 WHEN upper(c.type) LIKE '%BOOL%' THEN 16
    WHEN upper(c.type) LIKE '%CHAR%' OR upper(c.type) LIKE '%CLOB%' OR upper(c.type) LIKE '%TEXT%' THEN 12
    WHEN upper(c.type) LIKE '%BLOB%' THEN 2004 WHEN upper(c.type) LIKE '%REAL%' OR upper(c.type) LIKE '%FLOA%' OR upper(c.type) LIKE '%DOUB%' THEN 8
    WHEN upper(c.type) LIKE '%DATETIME%' OR upper(c.type) LIKE '%TIMESTAMP%' THEN 93 WHEN upper(c.type) LIKE '%DATE%' THEN 91 WHEN upper(c.type) LIKE '%TIME%' THEN 92
    WHEN upper(c.type) LIKE '%DEC%' OR upper(c.type) LIKE '%NUM%' THEN 2 ELSE 1111 END"""

internal object SqliteMetadata : MetadataQueries {
    override fun tableTypes() = MetadataQuery("SELECT 'TABLE' AS TABLE_TYPE UNION ALL SELECT 'VIEW'")

    override fun tables(catalog: String?, schemaPattern: String?, tablePattern: String?, types: List<String>?): MetadataQuery {
        val wanted = (types ?: listOf("TABLE", "VIEW")).map { it.lowercase() }
        return MetadataQuery(
            """SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, name AS TABLE_NAME, upper(type) AS TABLE_TYPE, NULL AS REMARKS,
                 NULL AS TYPE_CAT, NULL AS TYPE_SCHEM, NULL AS TYPE_NAME, NULL AS SELF_REFERENCING_COL_NAME, NULL AS REF_GENERATION
               FROM sqlite_schema
               WHERE name NOT LIKE 'sqlite\_%' ESCAPE '\' AND (? IS NULL OR name LIKE ?) AND type IN (${wanted.joinToString(",") { "?" }})
               ORDER BY 4, 3""",
            listOf(tablePattern, tablePattern) + wanted,
        )
    }

    override fun columns(catalog: String?, schemaPattern: String?, tablePattern: String?, columnPattern: String?) = MetadataQuery(
        """SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, t.name AS TABLE_NAME, c.name AS COLUMN_NAME, $JDBC_TYPE AS DATA_TYPE,
             c.type AS TYPE_NAME, 0 AS COLUMN_SIZE, NULL AS BUFFER_LENGTH, 0 AS DECIMAL_DIGITS, 10 AS NUM_PREC_RADIX,
             CASE WHEN c."notnull" OR c.pk THEN 0 ELSE 1 END AS NULLABLE, NULL AS REMARKS, c.dflt_value AS COLUMN_DEF,
             NULL AS SQL_DATA_TYPE, NULL AS SQL_DATETIME_SUB, NULL AS CHAR_OCTET_LENGTH, c.cid + 1 AS ORDINAL_POSITION,
             CASE WHEN c."notnull" OR c.pk THEN 'NO' ELSE 'YES' END AS IS_NULLABLE,
             NULL AS SCOPE_CATALOG, NULL AS SCOPE_SCHEMA, NULL AS SCOPE_TABLE, NULL AS SOURCE_DATA_TYPE,
             CASE WHEN c.pk = 1 AND upper(c.type) = 'INTEGER' THEN 'YES' ELSE 'NO' END AS IS_AUTOINCREMENT,
             CASE WHEN c.hidden IN (2, 3) THEN 'YES' ELSE 'NO' END AS IS_GENERATEDCOLUMN
           FROM sqlite_schema t JOIN pragma_table_xinfo(t.name) c
           WHERE t.type IN ('table', 'view') AND t.name NOT LIKE 'sqlite\_%' ESCAPE '\'
             AND (? IS NULL OR t.name LIKE ?) AND (? IS NULL OR c.name LIKE ?) AND c.hidden <> 1
           ORDER BY 3, c.cid""",
        listOf(tablePattern, tablePattern, columnPattern, columnPattern),
    )

    override fun primaryKeys(catalog: String?, schema: String?, table: String) = MetadataQuery(
        """SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, ? AS TABLE_NAME, name AS COLUMN_NAME, pk AS KEY_SEQ, NULL AS PK_NAME
           FROM pragma_table_info(?) WHERE pk > 0 ORDER BY pk""",
        listOf(table, table),
    )

    private const val RULE = "CASE %s WHEN 'CASCADE' THEN 0 WHEN 'RESTRICT' THEN 1 WHEN 'SET NULL' THEN 2 WHEN 'SET DEFAULT' THEN 4 ELSE 3 END"

    override fun importedKeys(catalog: String?, schema: String?, table: String) = MetadataQuery(
        """SELECT NULL AS PKTABLE_CAT, NULL AS PKTABLE_SCHEM, f."table" AS PKTABLE_NAME, f."to" AS PKCOLUMN_NAME,
             NULL AS FKTABLE_CAT, NULL AS FKTABLE_SCHEM, ? AS FKTABLE_NAME, f."from" AS FKCOLUMN_NAME, f.seq + 1 AS KEY_SEQ,
             ${RULE.replace("%s", "f.on_update")} AS UPDATE_RULE, ${RULE.replace("%s", "f.on_delete")} AS DELETE_RULE,
             NULL AS FK_NAME, NULL AS PK_NAME, 7 AS DEFERRABILITY
           FROM pragma_foreign_key_list(?) f ORDER BY 3, 9""",
        listOf(table, table),
    )

    override fun exportedKeys(catalog: String?, schema: String?, table: String) = MetadataQuery(
        """SELECT NULL AS PKTABLE_CAT, NULL AS PKTABLE_SCHEM, f."table" AS PKTABLE_NAME, f."to" AS PKCOLUMN_NAME,
             NULL AS FKTABLE_CAT, NULL AS FKTABLE_SCHEM, t.name AS FKTABLE_NAME, f."from" AS FKCOLUMN_NAME, f.seq + 1 AS KEY_SEQ,
             ${RULE.replace("%s", "f.on_update")} AS UPDATE_RULE, ${RULE.replace("%s", "f.on_delete")} AS DELETE_RULE,
             NULL AS FK_NAME, NULL AS PK_NAME, 7 AS DEFERRABILITY
           FROM sqlite_schema t JOIN pragma_foreign_key_list(t.name) f
           WHERE t.type = 'table' AND f."table" = ? COLLATE NOCASE ORDER BY 7, 9""",
        listOf(table),
    )

    override fun indexInfo(catalog: String?, schema: String?, table: String, unique: Boolean) = MetadataQuery(
        """SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, ? AS TABLE_NAME, CASE i."unique" WHEN 1 THEN 0 ELSE 1 END AS NON_UNIQUE,
             NULL AS INDEX_QUALIFIER, i.name AS INDEX_NAME, 3 AS TYPE, c.seqno + 1 AS ORDINAL_POSITION, c.name AS COLUMN_NAME,
             'A' AS ASC_OR_DESC, 0 AS CARDINALITY, 0 AS PAGES, NULL AS FILTER_CONDITION
           FROM pragma_index_list(?) i JOIN pragma_index_info(i.name) c
           WHERE ? = 0 OR i."unique" = 1 ORDER BY 4, 6, 8""",
        listOf(table, table, if (unique) 1 else 0),
    )
}
