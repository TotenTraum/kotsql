package dev.ksql

/** Database-independent classification of an error, derived from SQLSTATE where possible. */
enum class SqlErrorKind { CONNECTION, AUTHENTICATION, SYNTAX, PERMISSION, INTEGRITY, DATA, ROLLBACK, TIMEOUT, UNSUPPORTED, OTHER;

    companion object {
        fun ofSqlState(state: String?): SqlErrorKind = when {
            state == null || state.length < 2 -> OTHER
            state == "57014" || state == "70100" -> TIMEOUT
            state == "42501" -> PERMISSION
            else -> when (state.take(2)) {
                "08", "57" -> CONNECTION
                "28" -> AUTHENTICATION
                "42" -> SYNTAX
                "23" -> INTEGRITY
                "22" -> DATA
                "40" -> ROLLBACK
                "0A" -> UNSUPPORTED
                else -> OTHER
            }
        }
    }
}

/** Which kind of integrity constraint a statement violated. */
enum class ConstraintViolation { UNIQUE, FOREIGN_KEY, NOT_NULL, CHECK, EXCLUSION }

/**
 * What the database said about an error, apart from the message. Everything is optional: what is available depends on the
 * database and on the error, and fields hold the names as the server gives them.
 *
 * [detail] can include values from the data, such as the key that already exists, so take care where it gets logged.
 *
 * @param violation the kind of constraint violated, if that's what happened
 * @param constraint the constraint's or the unique index's name
 * @param position where in the SQL the error is, as a 1-based character position
 */
data class SqlErrorDetails(
    val violation: ConstraintViolation? = null,
    val constraint: String? = null,
    val schema: String? = null,
    val table: String? = null,
    val column: String? = null,
    val dataType: String? = null,
    val severity: String? = null,
    val detail: String? = null,
    val hint: String? = null,
    val position: Int? = null,
    val where: String? = null,
    val routine: String? = null,
) {
    companion object { val NONE = SqlErrorDetails() }
}

open class SqlException(
    message: String,
    val sqlState: String? = null,
    val vendorCode: Int = 0,
    val kind: SqlErrorKind = SqlErrorKind.ofSqlState(sqlState),
    cause: Throwable? = null,
    val details: SqlErrorDetails = SqlErrorDetails.NONE,
) : Exception(message, cause)

/** A batch failed. [updateCounts] holds the counts of the items that ran before the failure. */
class SqlBatchException(val updateCounts: LongArray, cause: SqlException) :
    SqlException(cause.message ?: "Batch failed", cause.sqlState, cause.vendorCode, cause.kind, cause, cause.details)
