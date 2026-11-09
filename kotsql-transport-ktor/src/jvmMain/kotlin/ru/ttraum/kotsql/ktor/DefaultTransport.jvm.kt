package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.transport.TransportFactory

public actual val DefaultTransport: TransportFactory get() = KtorTransport
