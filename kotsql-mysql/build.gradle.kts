plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    jvmToolchain(17)
    jvm()
    macosArm64(); linuxX64(); linuxArm64()
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    sourceSets {
        commonMain.dependencies {
            api(project(":kotsql-transport"))
            implementation(libs.cryptography.core)
        }
        jvmMain.dependencies { implementation(libs.cryptography.jdk) }
        // The same OpenSSL build as kotsql-transport-ktor's TLS: a binary must not contain two versions of libcrypto.
        nativeMain.dependencies {
            implementation(libs.cryptography.openssl)
            implementation(libs.openssl.libcrypto)
        }
    }
}
