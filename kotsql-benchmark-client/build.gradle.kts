plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    jvmToolchain(17)
    jvm()
    listOf(linuxX64(), linuxArm64()).forEach { target ->
        target.binaries.executable { entryPoint = "ru.ttraum.kotsql.benchmark.client.main" }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kotsql-core"))
            implementation(project(":kotsql-postgres"))
            implementation(project(":kotsql-transport-ktor"))
        }
    }
}

/** The JVM version as plain jars, to run in a container. */
tasks.register<Sync>("installJvm") {
    from(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
    into(layout.buildDirectory.dir("install/jvm"))
}
