plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    jvmToolchain(17)
    jvm()
    macosArm64(); linuxX64(); linuxArm64()
    sourceSets {
        commonMain.dependencies { api(project(":kotsql-core")) }
    }
}
