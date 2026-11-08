import java.net.URI
import java.security.MessageDigest

plugins { alias(libs.plugins.kotlin.multiplatform) }

// The version that androidx.sqlite bundles, so that every platform has the same SQLite.
val sqliteAmalgamation = "sqlite-amalgamation-3500100"
val sqliteUrl = "https://www.sqlite.org/2025/$sqliteAmalgamation.zip"
val sqliteSha256 = "41716b44ac8777188c4c3f1f370f01c9cb9e3b6428eb5c981d086c35de2d9d3f"
val sqliteDir = layout.buildDirectory.dir("sqlite")

val downloadSqlite by tasks.registering {
    description = "Downloads and checks the SQLite amalgamation for the Windows build."
    val zip = layout.buildDirectory.file("sqlite/$sqliteAmalgamation.zip")
    val source = sqliteDir.map { it.dir(sqliteAmalgamation) }
    outputs.dir(source)
    doLast {
        val file = zip.get().asFile
        if (!file.exists()) {
            file.parentFile.mkdirs()
            URI(sqliteUrl).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (digest != sqliteSha256) {
            file.delete()
            throw GradleException("$sqliteUrl has SHA-256 $digest, expected $sqliteSha256")
        }
        project.copy { from(project.zipTree(file)); into(sqliteDir) }
    }
}

// What androidx.sqlite's build reports in PRAGMA compile_options, less what is the default or specific to its platforms.
val sqliteOptions = listOf(
    "SQLITE_THREADSAFE=2", "SQLITE_DEFAULT_MEMSTATUS=0", "SQLITE_DEFAULT_FILE_FORMAT=4", "SQLITE_DEFAULT_RECURSIVE_TRIGGERS",
    "SQLITE_DEFAULT_WAL_SYNCHRONOUS=1", "SQLITE_DIRECT_OVERFLOW_READ", "SQLITE_ENABLE_COLUMN_METADATA", "SQLITE_ENABLE_FTS3",
    "SQLITE_ENABLE_FTS3_PARENTHESIS", "SQLITE_ENABLE_FTS4", "SQLITE_ENABLE_FTS5", "SQLITE_ENABLE_MATH_FUNCTIONS",
    "SQLITE_ENABLE_NORMALIZE", "SQLITE_ENABLE_RTREE", "SQLITE_ENABLE_STAT4", "SQLITE_MAX_VARIABLE_NUMBER=32766",
    "SQLITE_OMIT_DEPRECATED", "SQLITE_OMIT_PROGRESS_CALLBACK", "SQLITE_OMIT_SHARED_CACHE", "SQLITE_SECURE_DELETE",
    "SQLITE_TEMP_STORE=3",
)

kotlin {
    jvmToolchain(17)
    jvm()
    macosArm64(); linuxX64(); linuxArm64()
    mingwX64 {
        compilations.getByName("main").cinterops.create("sqlite3") {
            val source = sqliteDir.get().dir(sqliteAmalgamation).asFile
            includeDirs(source)
            // Compiling a source into the library takes the older way of calling C, through generated bridges.
            extraOpts("-Xccall-mode", "indirect", "-Xcompile-source", source.resolve("sqlite3.c").path)
            (sqliteOptions + "NDEBUG").forEach { extraOpts("-Xsource-compiler-option", "-D$it") }
            // As C: the compiler is clang++.
            extraOpts("-Xsource-compiler-option", "-xc", "-Xsource-compiler-option", "-O2")
        }
    }
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    // androidx.sqlite's bundled SQLite where it has a build; the driver's own build of the same version on Windows.
    applyDefaultHierarchyTemplate {
        common {
            group("bundled") {
                withJvm()
                withLinux()
                withMacos()
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kotsql-core"))
        }
        getByName("bundledMain").dependencies {
            implementation(libs.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

tasks.matching { it.name == "cinteropSqlite3MingwX64" }.configureEach { dependsOn(downloadSqlite) }
