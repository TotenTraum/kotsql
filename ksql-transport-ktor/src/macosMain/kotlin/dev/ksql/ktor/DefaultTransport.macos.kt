package dev.ksql.ktor

import dev.ksql.spi.TransportFactory

actual val DefaultTransport: TransportFactory get() = KtorTransport
