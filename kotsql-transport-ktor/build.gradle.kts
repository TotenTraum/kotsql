import java.time.Duration

plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    jvmToolchain(17)
    jvm()
    listOf(macosArm64(), linuxX64(), linuxArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("openssl")
    }
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    sourceSets {
        commonMain.dependencies {
            api(project(":kotsql-transport"))
            implementation(libs.ktor.network)
        }
        // ktor implements TLS only on the JVM; native targets use OpenSSL.
        jvmMain.dependencies { implementation(libs.ktor.network.tls) }
        nativeMain.dependencies {
            implementation(libs.openssl.libssl)
            implementation(libs.openssl.libcrypto)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":kotsql-postgres"))
            implementation(project(":kotsql-mysql"))
            implementation(project(":kotsql-sqlite"))
            implementation(libs.coroutines.test)
        }
    }
}

// A hang in the driver can be out of reach of a test's own timeout: it should fail the build, not keep it waiting.
tasks.withType<Test>().configureEach { timeout.set(Duration.ofMinutes(20)) }
