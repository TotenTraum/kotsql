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
            api(project(":ksql-core"))
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
            implementation(project(":ksql-postgres"))
            implementation(project(":ksql-mysql"))
            implementation(libs.coroutines.test)
        }
    }
}
