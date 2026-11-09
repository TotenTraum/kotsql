plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.publish)
}

description = "kotsql: what drivers of databases behind a socket share: transports, TLS options, wire primitives and failover"

kotlin {
    jvmToolchain(17)
    jvm()
    macosArm64(); linuxX64(); linuxArm64(); mingwX64()
    sourceSets {
        commonMain.dependencies { api(project(":kotsql-core")) }
    }
}
