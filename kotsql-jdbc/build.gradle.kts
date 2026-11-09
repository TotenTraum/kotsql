plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.publish)
}

description = "kotsql: JDBC driver over any kotsql driver"

kotlin {
    jvmToolchain(17)
    jvm()
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    sourceSets {
        jvmMain.dependencies {
            api(project(":kotsql-core"))
            implementation(project(":kotsql-transport"))
            implementation(project(":kotsql-jvm-values"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":kotsql-postgres"))
            implementation(project(":kotsql-mysql"))
            implementation(project(":kotsql-sqlite"))
            implementation(libs.flyway.mysql)
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.launcher)
            implementation(libs.testcontainers.postgresql)
            implementation(libs.hikari)
            implementation(libs.flyway.core)
            implementation(libs.flyway.postgresql)
            implementation(libs.jooq)
        }
    }
}
