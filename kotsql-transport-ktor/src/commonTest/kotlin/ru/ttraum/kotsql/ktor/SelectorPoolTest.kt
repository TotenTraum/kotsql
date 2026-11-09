package ru.ttraum.kotsql.ktor

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SelectorPoolTest {
    @Test fun spreadsSocketsAndClosesEmptiedSelectors() = runTest {
        val pool = SelectorPool(limit = 2)
        val leases = List(5) { pool.acquire() }
        assertEquals(3, pool.size())
        assertSame(leases[0].selector, leases[1].selector)
        assertNotSame(leases[1].selector, leases[2].selector)
        leases[4].release()
        leases[4].release() // twice is once
        assertEquals(2, pool.size())
        leases.take(4).forEach { it.release() }
        // The last one to be emptied stays, for the next connection.
        assertEquals(1, pool.size())
        assertSame(leases[3].selector.manager, pool.acquire().selector.manager)
    }

    @Test fun replacesADeadSelector() = runTest {
        val pool = SelectorPool(limit = 10)
        val first = pool.acquire()
        first.selectorDied()
        val second = pool.acquire()
        assertNotSame(first.selector, second.selector)
        first.release()
        assertEquals(1, pool.size())
        second.release()
    }
}

class SpuriousConnectFailureTest {
    @Test fun onlyErrorZero() {
        kotlin.test.assertTrue(isSpuriousConnectFailure(RuntimeException("Failed to connect", RuntimeException("POSIX error 0: Success (0)"))))
        kotlin.test.assertFalse(isSpuriousConnectFailure(RuntimeException("Failed to connect", RuntimeException("POSIX error 111: Connection refused (111)"))))
        kotlin.test.assertFalse(isSpuriousConnectFailure(RuntimeException("POSIX error 10061: refused")))
    }
}
