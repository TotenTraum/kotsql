plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    jvmToolchain(17)
    jvm()
    macosArm64(); linuxX64(); linuxArm64()
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    sourceSets {
        commonMain.dependencies {
            api(project(":kotsql-core"))
            implementation(libs.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
