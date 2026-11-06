plugins { alias(libs.plugins.kotlin.multiplatform) }

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
            api(project(":ksql-core"))
            api(libs.r2dbc.spi)
            implementation(libs.coroutines.reactive)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":ksql-postgres"))
            implementation(project(":ksql-mysql"))
            implementation(project(":ksql-jdbc"))
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
