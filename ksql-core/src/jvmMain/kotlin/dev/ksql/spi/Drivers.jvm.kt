package dev.ksql.spi

import java.util.ServiceLoader

internal actual fun discoverPlatformDrivers(): List<SqlDriver> {
    val loaders = listOfNotNull(Thread.currentThread().contextClassLoader, SqlDriver::class.java.classLoader).distinct()
    return loaders.flatMap { ServiceLoader.load(SqlDriver::class.java, it).toList() }.distinctBy { it::class }
}
