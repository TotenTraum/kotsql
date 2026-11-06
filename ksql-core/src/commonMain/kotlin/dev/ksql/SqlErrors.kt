@file:kotlin.jvm.JvmName("SqlErrors")

package dev.ksql

/**
 * The details of the [SqlException] behind [this], however deep, or null if there is none. They survive the wrapping by JDBC,
 * R2DBC and frameworks such as Spring, whose exceptions all have it as a cause. From Java: `SqlErrors.sqlErrorDetails(e)`.
 */
fun Throwable.sqlErrorDetails(): SqlErrorDetails? {
    var current: Throwable? = this
    // Bounded, since a cause can form a cycle in a badly written wrapper.
    repeat(64) {
        val e = current ?: return null
        if (e is SqlException) return e.details
        current = e.cause
    }
    return null
}
