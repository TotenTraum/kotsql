package dev.ksql.postgres

import dev.ksql.MetadataQueries
import dev.ksql.MetadataQuery

/** `DATA_TYPE` values are the constants of `java.sql.Types`. They are fixed by the JDBC specification. */
private const val JDBC_TYPE = """CASE t.typname
    WHEN 'bool' THEN 16 WHEN 'int2' THEN 5 WHEN 'int4' THEN 4 WHEN 'int8' THEN -5 WHEN 'oid' THEN -5
    WHEN 'float4' THEN 7 WHEN 'float8' THEN 8 WHEN 'numeric' THEN 2 WHEN 'bpchar' THEN 1 WHEN 'char' THEN 1
    WHEN 'varchar' THEN 12 WHEN 'text' THEN 12 WHEN 'name' THEN 12 WHEN 'bytea' THEN -2 WHEN 'date' THEN 91
    WHEN 'time' THEN 92 WHEN 'timetz' THEN 2013 WHEN 'timestamp' THEN 93 WHEN 'timestamptz' THEN 2014
    ELSE CASE WHEN t.typcategory = 'A' THEN 2003 ELSE 1111 END END"""

internal object PgMetadata : MetadataQueries {
    private val TABLE_KINDS = linkedMapOf(
        "TABLE" to "r", "PARTITIONED TABLE" to "p", "VIEW" to "v", "MATERIALIZED VIEW" to "m",
        "FOREIGN TABLE" to "f", "SEQUENCE" to "S", "INDEX" to "i",
    )

    override fun catalogs() = MetadataQuery("SELECT current_database() AS \"TABLE_CAT\"")

    override fun schemas(catalog: String?, schemaPattern: String?) = MetadataQuery(
        """SELECT n.nspname AS "TABLE_SCHEM", current_database() AS "TABLE_CATALOG"
           FROM pg_catalog.pg_namespace n
           WHERE ($1::text IS NULL OR n.nspname LIKE $1) AND n.nspname !~ '^pg_(toast|temp)'
           ORDER BY 1""",
        listOf(schemaPattern),
    )

    override fun tableTypes() = MetadataQuery(
        TABLE_KINDS.keys.joinToString(" UNION ALL ") { "SELECT '$it' AS \"TABLE_TYPE\"" } + " ORDER BY 1",
    )

    override fun tables(catalog: String?, schemaPattern: String?, tablePattern: String?, types: List<String>?): MetadataQuery {
        val kinds = (types ?: listOf("TABLE", "PARTITIONED TABLE", "VIEW", "MATERIALIZED VIEW", "FOREIGN TABLE"))
            .mapNotNull { TABLE_KINDS[it] }
        val kindCase = TABLE_KINDS.entries.joinToString(" ") { "WHEN '${it.value}' THEN '${it.key}'" }
        val kindList = kinds.joinToString(",") { "'$it'" }.ifEmpty { "''" }
        return MetadataQuery(
            """SELECT current_database() AS "TABLE_CAT", n.nspname AS "TABLE_SCHEM", c.relname AS "TABLE_NAME",
                 CASE c.relkind::text $kindCase END AS "TABLE_TYPE",
                 d.description AS "REMARKS", NULL::text AS "TYPE_CAT", NULL::text AS "TYPE_SCHEM",
                 NULL::text AS "TYPE_NAME", NULL::text AS "SELF_REFERENCING_COL_NAME", NULL::text AS "REF_GENERATION"
               FROM pg_catalog.pg_class c
               JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
               LEFT JOIN pg_catalog.pg_description d ON d.objoid = c.oid AND d.objsubid = 0
                 AND d.classoid = 'pg_catalog.pg_class'::regclass
               WHERE c.relkind::text IN ($kindList)
                 AND ($1::text IS NULL OR n.nspname LIKE $1) AND ($2::text IS NULL OR c.relname LIKE $2)
               ORDER BY 4, 2, 3""",
            listOf(schemaPattern, tablePattern),
        )
    }

    override fun columns(catalog: String?, schemaPattern: String?, tablePattern: String?, columnPattern: String?) = MetadataQuery(
        """SELECT current_database() AS "TABLE_CAT", n.nspname AS "TABLE_SCHEM", c.relname AS "TABLE_NAME",
             a.attname AS "COLUMN_NAME", ($JDBC_TYPE)::int AS "DATA_TYPE", t.typname AS "TYPE_NAME",
             COALESCE(information_schema._pg_char_max_length(a.atttypid, a.atttypmod),
                      information_schema._pg_numeric_precision(a.atttypid, a.atttypmod),
                      information_schema._pg_datetime_precision(a.atttypid, a.atttypmod),
                      2147483647)::int AS "COLUMN_SIZE",
             NULL::int AS "BUFFER_LENGTH",
             information_schema._pg_numeric_scale(a.atttypid, a.atttypmod)::int AS "DECIMAL_DIGITS",
             10 AS "NUM_PREC_RADIX",
             CASE WHEN a.attnotnull THEN 0 ELSE 1 END AS "NULLABLE",
             d.description AS "REMARKS", pg_catalog.pg_get_expr(ad.adbin, ad.adrelid) AS "COLUMN_DEF",
             NULL::int AS "SQL_DATA_TYPE", NULL::int AS "SQL_DATETIME_SUB",
             information_schema._pg_char_octet_length(a.atttypid, a.atttypmod)::int AS "CHAR_OCTET_LENGTH",
             a.attnum::int AS "ORDINAL_POSITION",
             CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END AS "IS_NULLABLE",
             NULL::text AS "SCOPE_CATALOG", NULL::text AS "SCOPE_SCHEMA", NULL::text AS "SCOPE_TABLE",
             NULL::smallint AS "SOURCE_DATA_TYPE",
             CASE WHEN a.attidentity <> '' OR pg_catalog.pg_get_expr(ad.adbin, ad.adrelid) LIKE 'nextval(%' THEN 'YES' ELSE 'NO' END AS "IS_AUTOINCREMENT",
             CASE WHEN a.attgenerated <> '' THEN 'YES' ELSE 'NO' END AS "IS_GENERATEDCOLUMN"
           FROM pg_catalog.pg_attribute a
           JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
           JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
           JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
           LEFT JOIN pg_catalog.pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
           LEFT JOIN pg_catalog.pg_description d ON d.objoid = c.oid AND d.objsubid = a.attnum
             AND d.classoid = 'pg_catalog.pg_class'::regclass
           WHERE a.attnum > 0 AND NOT a.attisdropped AND c.relkind IN ('r','p','v','m','f')
             AND ($1::text IS NULL OR n.nspname LIKE $1) AND ($2::text IS NULL OR c.relname LIKE $2)
             AND ($3::text IS NULL OR a.attname LIKE $3)
           ORDER BY 2, 3, 17""",
        listOf(schemaPattern, tablePattern, columnPattern),
    )

    override fun primaryKeys(catalog: String?, schema: String?, table: String) = MetadataQuery(
        """SELECT current_database() AS "TABLE_CAT", n.nspname AS "TABLE_SCHEM", c.relname AS "TABLE_NAME",
             a.attname AS "COLUMN_NAME", k.ord::smallint AS "KEY_SEQ", con.conname AS "PK_NAME"
           FROM pg_catalog.pg_constraint con
           JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
           JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
           CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
           JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
           WHERE con.contype = 'p' AND ($1::text IS NULL OR n.nspname = $1) AND c.relname = $2
           ORDER BY 4""",
        listOf(schema, table),
    )

    private fun foreignKeys(filter: String, schema: String?, table: String) = MetadataQuery(
        """SELECT current_database() AS "PKTABLE_CAT", pn.nspname AS "PKTABLE_SCHEM", pc.relname AS "PKTABLE_NAME",
             pa.attname AS "PKCOLUMN_NAME", current_database() AS "FKTABLE_CAT", fn.nspname AS "FKTABLE_SCHEM",
             fc.relname AS "FKTABLE_NAME", fa.attname AS "FKCOLUMN_NAME", k.ord::smallint AS "KEY_SEQ",
             (CASE con.confupdtype WHEN 'c' THEN 0 WHEN 'r' THEN 1 WHEN 'n' THEN 2 WHEN 'd' THEN 4 ELSE 3 END)::smallint AS "UPDATE_RULE",
             (CASE con.confdeltype WHEN 'c' THEN 0 WHEN 'r' THEN 1 WHEN 'n' THEN 2 WHEN 'd' THEN 4 ELSE 3 END)::smallint AS "DELETE_RULE",
             con.conname AS "FK_NAME", NULL::text AS "PK_NAME",
             (CASE WHEN NOT con.condeferrable THEN 7 WHEN con.condeferred THEN 5 ELSE 6 END)::smallint AS "DEFERRABILITY"
           FROM pg_catalog.pg_constraint con
           JOIN pg_catalog.pg_class fc ON fc.oid = con.conrelid
           JOIN pg_catalog.pg_namespace fn ON fn.oid = fc.relnamespace
           JOIN pg_catalog.pg_class pc ON pc.oid = con.confrelid
           JOIN pg_catalog.pg_namespace pn ON pn.oid = pc.relnamespace
           CROSS JOIN LATERAL unnest(con.conkey, con.confkey) WITH ORDINALITY AS k(fnum, pnum, ord)
           JOIN pg_catalog.pg_attribute fa ON fa.attrelid = fc.oid AND fa.attnum = k.fnum
           JOIN pg_catalog.pg_attribute pa ON pa.attrelid = pc.oid AND pa.attnum = k.pnum
           WHERE con.contype = 'f' AND $filter
           ORDER BY 1, 2, 3, 9""",
        listOf(schema, table),
    )

    override fun importedKeys(catalog: String?, schema: String?, table: String) =
        foreignKeys("($1::text IS NULL OR fn.nspname = $1) AND fc.relname = $2", schema, table)

    override fun exportedKeys(catalog: String?, schema: String?, table: String) =
        foreignKeys("($1::text IS NULL OR pn.nspname = $1) AND pc.relname = $2", schema, table)

    override fun indexInfo(catalog: String?, schema: String?, table: String, unique: Boolean) = MetadataQuery(
        """SELECT current_database() AS "TABLE_CAT", n.nspname AS "TABLE_SCHEM", c.relname AS "TABLE_NAME",
             NOT i.indisunique AS "NON_UNIQUE", NULL::text AS "INDEX_QUALIFIER", ic.relname AS "INDEX_NAME",
             3::smallint AS "TYPE", k.ord::smallint AS "ORDINAL_POSITION", a.attname AS "COLUMN_NAME",
             'A' AS "ASC_OR_DESC", ic.reltuples::bigint AS "CARDINALITY", ic.relpages::bigint AS "PAGES",
             pg_catalog.pg_get_expr(i.indpred, i.indrelid) AS "FILTER_CONDITION"
           FROM pg_catalog.pg_index i
           JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
           JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
           JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
           CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
           JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
           WHERE ($1::text IS NULL OR n.nspname = $1) AND c.relname = $2 AND (NOT $3::bool OR i.indisunique)
           ORDER BY 4, 6, 8""",
        listOf(schema, table, unique),
    )

    override fun typeInfo() = MetadataQuery(
        """SELECT t.typname AS "TYPE_NAME", ($JDBC_TYPE)::int AS "DATA_TYPE",
             2147483647 AS "PRECISION", NULL::text AS "LITERAL_PREFIX", NULL::text AS "LITERAL_SUFFIX",
             NULL::text AS "CREATE_PARAMS", 1::smallint AS "NULLABLE", true AS "CASE_SENSITIVE",
             3::smallint AS "SEARCHABLE", false AS "UNSIGNED_ATTRIBUTE", false AS "FIXED_PREC_SCALE",
             false AS "AUTO_INCREMENT", t.typname AS "LOCAL_TYPE_NAME", 0::smallint AS "MINIMUM_SCALE",
             0::smallint AS "MAXIMUM_SCALE", NULL::int AS "SQL_DATA_TYPE", NULL::int AS "SQL_DATETIME_SUB",
             10 AS "NUM_PREC_RADIX"
           FROM pg_catalog.pg_type t
           JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
           WHERE n.nspname = 'pg_catalog' AND t.typtype = 'b' AND t.typname !~ '^_'
           ORDER BY 2, 1""",
    )
}
