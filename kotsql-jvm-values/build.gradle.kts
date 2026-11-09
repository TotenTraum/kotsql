plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.publish)
}

description = "kotsql: conversions between kotsql values and java.sql and java.time, shared by the JDBC and R2DBC adapters"

kotlin {
    jvmToolchain(17)
    jvm()
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    sourceSets {
        jvmMain.dependencies { api(project(":kotsql-core")) }
    }
}
