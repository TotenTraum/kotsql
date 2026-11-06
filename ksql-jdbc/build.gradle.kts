plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    jvmToolchain(17)
    jvm()
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    sourceSets {
        jvmMain.dependencies { api(project(":ksql-core")) }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":ksql-postgres"))
            implementation(project(":ksql-mysql"))
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
