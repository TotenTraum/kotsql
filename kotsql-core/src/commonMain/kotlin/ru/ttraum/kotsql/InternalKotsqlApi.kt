package ru.ttraum.kotsql

/**
 * Shared between kotsql's own modules, such as the wire primitives that the drivers read and write with. Not part of the
 * stable API: it may change in any release, and it is left out of the checked API dumps.
 */
@RequiresOptIn("This is kotsql's internal API, shared between its modules, and may change in any release", RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.TYPEALIAS)
public annotation class InternalKotsqlApi
