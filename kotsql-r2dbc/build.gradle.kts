plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.publish)
}

description = "kotsql: R2DBC driver over any kotsql driver"

kotlin {
    jvmToolchain(17)
    jvm()
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        // The SPI requires IllegalArgumentException for null arguments, while Kotlin's own checks would throw NPE first.
        freeCompilerArgs.add("-Xno-param-assertions")
    }
    sourceSets {
        jvmMain.dependencies {
            api(project(":kotsql-core"))
            implementation(project(":kotsql-transport"))
            implementation(project(":kotsql-jvm-values"))
            api(libs.r2dbc.spi)
            implementation(libs.coroutines.reactive)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":kotsql-postgres"))
            implementation(project(":kotsql-mysql"))
            implementation(project(":kotsql-sqlite"))
            implementation(project(":kotsql-jdbc"))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.launcher)
            implementation(libs.testcontainers.postgresql)
            implementation(libs.r2dbc.spi.test)
            implementation(libs.r2dbc.pool)
            implementation(libs.spring.jdbc)
            implementation(libs.spring.r2dbc)
            implementation(libs.hikari)
        }
    }
}
