package dev.ksql.jdbc

import dev.ksql.ExecuteOptions
import dev.ksql.IdentifierCase
import dev.ksql.IsolationLevel
import dev.ksql.MetadataQuery
import dev.ksql.SqlResult
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.RowIdLifetime

/** Facts about any ksql connection. Database-specific answers come from the dialect. */
internal class JdbcDatabaseMetaData(private val conn: JdbcConnection) : BaseDatabaseMetaData() {
    private val core get() = conn.core
    private val dialect get() = conn.dialect
    private val queries get() = dialect.metadata

    private fun results(query: MetadataQuery?): ResultSet {
        if (query == null) return emptyMetadata("")
        // Autocommit state is untouched: metadata queries never start a transaction by themselves.
        val cursor = conn.run { execute(query.sql, query.params, ExecuteOptions.DEFAULT) }
        val rows = conn.run { cursor.nextResult() } as? SqlResult.Rows ?: return emptyMetadata("")
        return JdbcResultSet(null, rows, conn, onClose = { conn.run { cursor.close() } })
    }

    override fun getConnection(): Connection = conn
    override fun getURL() = conn.jdbcUrl
    override fun getUserName() = conn.userName
    override fun isReadOnly() = conn.isReadOnly
    override fun getDatabaseProductName() = core.serverInfo.productName
    override fun getDatabaseProductVersion() = core.serverInfo.version
    override fun getDatabaseMajorVersion() = core.serverInfo.majorVersion
    override fun getDatabaseMinorVersion() = core.serverInfo.minorVersion
    override fun getDriverName() = DRIVER_NAME
    override fun getDriverVersion() = DRIVER_VERSION
    override fun getDriverMajorVersion() = 0
    override fun getDriverMinorVersion() = 1
    override fun getJDBCMajorVersion() = 4
    override fun getJDBCMinorVersion() = 2
    override fun getSQLStateType() = DatabaseMetaData.sqlStateSQL
    override fun getIdentifierQuoteString() = dialect.identifierQuote
    override fun getSearchStringEscape() = dialect.searchStringEscape
    override fun getCatalogSeparator() = "."
    override fun getCatalogTerm() = "database"
    override fun getSchemaTerm() = "schema"
    override fun getProcedureTerm() = "function"
    override fun getExtraNameCharacters() = ""
    override fun isCatalogAtStart() = true

    override fun storesLowerCaseIdentifiers() = dialect.identifierCase == IdentifierCase.LOWER
    override fun storesUpperCaseIdentifiers() = dialect.identifierCase == IdentifierCase.UPPER
    override fun storesMixedCaseIdentifiers() = dialect.identifierCase == IdentifierCase.AS_WRITTEN
    override fun supportsMixedCaseIdentifiers() = dialect.identifierCase == IdentifierCase.AS_WRITTEN
    override fun storesMixedCaseQuotedIdentifiers() = true
    override fun supportsMixedCaseQuotedIdentifiers() = true

    override fun supportsTransactions() = true
    override fun supportsMultipleTransactions() = true
    override fun supportsSavepoints() = true
    override fun supportsBatchUpdates() = true
    override fun supportsGetGeneratedKeys() = true
    override fun supportsNamedParameters() = false
    override fun supportsMultipleResultSets() = true
    override fun supportsStatementPooling() = false
    override fun getDefaultTransactionIsolation() = when (dialect.defaultIsolation) {
        IsolationLevel.READ_UNCOMMITTED -> Connection.TRANSACTION_READ_UNCOMMITTED
        IsolationLevel.READ_COMMITTED -> Connection.TRANSACTION_READ_COMMITTED
        IsolationLevel.REPEATABLE_READ -> Connection.TRANSACTION_REPEATABLE_READ
        IsolationLevel.SERIALIZABLE -> Connection.TRANSACTION_SERIALIZABLE
    }
    override fun supportsTransactionIsolationLevel(p0: Int) = p0 != Connection.TRANSACTION_NONE
    override fun supportsDataDefinitionAndDataManipulationTransactions() = true
    override fun supportsResultSetType(p0: Int) = p0 != ResultSet.TYPE_SCROLL_SENSITIVE
    override fun supportsResultSetConcurrency(p0: Int, p1: Int) = supportsResultSetType(p0) && p1 == ResultSet.CONCUR_READ_ONLY
    override fun supportsResultSetHoldability(p0: Int) = p0 == ResultSet.HOLD_CURSORS_OVER_COMMIT
    override fun getResultSetHoldability() = ResultSet.HOLD_CURSORS_OVER_COMMIT
    override fun getRowIdLifetime() = RowIdLifetime.ROWID_UNSUPPORTED
    override fun supportsSchemasInDataManipulation() = true
    override fun supportsSchemasInTableDefinitions() = true
    override fun supportsSchemasInIndexDefinitions() = true
    override fun supportsSchemasInProcedureCalls() = true
    override fun supportsSchemasInPrivilegeDefinitions() = true
    override fun supportsAlterTableWithAddColumn() = true
    override fun supportsAlterTableWithDropColumn() = true
    override fun supportsColumnAliasing() = true
    override fun supportsGroupBy() = true
    override fun supportsGroupByUnrelated() = true
    override fun supportsOrderByUnrelated() = true
    override fun supportsLikeEscapeClause() = true
    override fun supportsNonNullableColumns() = true
    override fun supportsOuterJoins() = true
    override fun supportsFullOuterJoins() = true
    override fun supportsLimitedOuterJoins() = true
    override fun supportsSubqueriesInComparisons() = true
    override fun supportsSubqueriesInExists() = true
    override fun supportsSubqueriesInIns() = true
    override fun supportsSubqueriesInQuantifieds() = true
    override fun supportsCorrelatedSubqueries() = true
    override fun supportsUnion() = true
    override fun supportsUnionAll() = true
    override fun supportsANSI92EntryLevelSQL() = true
    override fun supportsMinimumSQLGrammar() = true
    override fun supportsCoreSQLGrammar() = true
    override fun nullsAreSortedHigh() = true
    override fun allTablesAreSelectable() = true

    override fun getCatalogs() = results(queries.catalogs())
    override fun getSchemas() = results(queries.schemas(null, null))
    override fun getSchemas(p0: String?, p1: String?) = results(queries.schemas(p0, p1))
    override fun getTableTypes() = results(queries.tableTypes())
    override fun getTables(p0: String?, p1: String?, p2: String?, p3: Array<out String>?) = results(queries.tables(p0, p1, p2, p3?.toList()))
    override fun getColumns(p0: String?, p1: String?, p2: String?, p3: String?) = results(queries.columns(p0, p1, p2, p3))
    override fun getPrimaryKeys(p0: String?, p1: String?, p2: String?) = results(queries.primaryKeys(p0, p1, p2!!))
    override fun getImportedKeys(p0: String?, p1: String?, p2: String?) = results(queries.importedKeys(p0, p1, p2!!))
    override fun getExportedKeys(p0: String?, p1: String?, p2: String?) = results(queries.exportedKeys(p0, p1, p2!!))
    override fun getIndexInfo(p0: String?, p1: String?, p2: String?, p3: Boolean, p4: Boolean) = results(queries.indexInfo(p0, p1, p2!!, p3))
    override fun getTypeInfo() = results(queries.typeInfo())

    override fun <T> unwrap(p0: Class<T>?): T = if (p0 != null && p0.isInstance(this)) p0.cast(this) else throw java.sql.SQLException("Not a wrapper of $p0")
    override fun isWrapperFor(p0: Class<*>?) = p0 != null && p0.isInstance(this)
}
