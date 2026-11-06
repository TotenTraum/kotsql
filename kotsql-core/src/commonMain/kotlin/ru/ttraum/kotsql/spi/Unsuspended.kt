package ru.ttraum.kotsql.spi

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs suspending driver code on the current thread with no dispatcher, for use with a blocking [Transport]: since
 * such a transport never actually suspends, [block] runs to completion before this returns, and blocking callers such
 * as JDBC need no `runBlocking` or extra threads. Fails if [block] does suspend.
 */
fun <T> runUnsuspended(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return (outcome ?: error("A blocking transport suspended; driver code must not suspend on anything else")).getOrThrow()
}
