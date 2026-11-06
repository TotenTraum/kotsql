package dev.ksql.ktor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostMatchingTest {
    private val dns = 2
    private val ip = 7

    @Test fun exactAndWildcardNames() {
        val names = listOf(dns to "db.example.com", dns to "*.corp.example.com")
        assertTrue(certificateMatchesHost(names, "CN=x", "db.example.com"))
        assertTrue(certificateMatchesHost(names, "CN=x", "DB.Example.COM."))
        assertTrue(certificateMatchesHost(names, "CN=x", "a.corp.example.com"))
        assertFalse(certificateMatchesHost(names, "CN=x", "a.b.corp.example.com"))
        assertFalse(certificateMatchesHost(names, "CN=x", "corp.example.com"))
        assertFalse(certificateMatchesHost(names, "CN=x", "evil.com"))
        assertFalse(certificateMatchesHost(listOf(dns to "*.com"), "CN=x", "example.com"))
    }

    @Test fun commonNameIsOnlyAFallback() {
        assertTrue(certificateMatchesHost(emptyList(), "O=a,CN=db.example.com", "db.example.com"))
        assertFalse(certificateMatchesHost(listOf(dns to "other.example.com"), "CN=db.example.com", "db.example.com"))
        assertFalse(certificateMatchesHost(emptyList(), "O=a", "db.example.com"))
    }

    @Test fun ipAddressesNeedAnIpEntry() {
        assertTrue(certificateMatchesHost(listOf(ip to "10.0.0.1"), "CN=x", "10.0.0.1"))
        assertTrue(certificateMatchesHost(listOf(ip to "0:0:0:0:0:0:0:1"), "CN=x", "::1"))
        assertFalse(certificateMatchesHost(listOf(ip to "10.0.0.2"), "CN=x", "10.0.0.1"))
        assertFalse(certificateMatchesHost(listOf(dns to "10.0.0.1"), "CN=10.0.0.1", "10.0.0.1"))
        assertFalse(certificateMatchesHost(emptyList(), "CN=10.0.0.1", "10.0.0.1"))
    }
}
