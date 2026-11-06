package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.transport.TransportFactory

actual val DefaultTransport: TransportFactory get() = KtorTransport
