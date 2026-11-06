package dev.ksql.ktor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

class SocketOptionsTest {
    /** Reads the options back from the kernel, as ktor quietly drops them from its builder on native targets. */
    @Test fun connectionsHaveNoDelayAndKeepAlive() = runTest {
        val port = testEnv("KSQL_TEST_TLS_PORT")?.toInt() ?: return@runTest println("KSQL_TEST_TLS_PORT is not set; skipping")
        withContext(Dispatchers.Default) {
            val transport = KtorTransport.connect("localhost", port, 5_000) as KtorTransport
            try {
                assertTrue(socketFlag(transport.socket, keepAlive = false), "TCP_NODELAY")
                assertTrue(socketFlag(transport.socket, keepAlive = true), "SO_KEEPALIVE")
            } finally {
                transport.close()
            }
        }
    }
}
