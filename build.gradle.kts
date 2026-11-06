plugins { alias(libs.plugins.kotlin.multiplatform) apply false }

allprojects {
    group = "ru.ttraum.kotsql"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}

/**
 * What the modules must not know of each other, beyond what their dependencies would allow by accident: a test or a tool
 * can bring a driver onto the classpath, and a comment isn't code, so this looks at the code itself.
 */
val checkModuleBoundaries by tasks.registering {
    val root = layout.projectDirectory.asFile
    doLast {
        val drivers = listOf("ru.ttraum.kotsql.postgres", "ru.ttraum.kotsql.mysql", "ru.ttraum.kotsql.sqlite")
        val networkOnly = listOf("ru.ttraum.kotsql.transport", "ru.ttraum.kotsql.ktor")
        val rules = mapOf(
            "kotsql-core" to drivers + networkOnly + listOf("ru.ttraum.kotsql.jvm", "ru.ttraum.kotsql.jdbc", "ru.ttraum.kotsql.r2dbc", "java.sql", "javax.net", "java.net", "io.ktor"),
            "kotsql-transport" to drivers + listOf("ru.ttraum.kotsql.ktor", "ru.ttraum.kotsql.jvm", "ru.ttraum.kotsql.jdbc", "ru.ttraum.kotsql.r2dbc", "io.ktor"),
            "kotsql-jvm-values" to drivers + networkOnly + listOf("ru.ttraum.kotsql.jdbc", "ru.ttraum.kotsql.r2dbc"),
            "kotsql-postgres" to drivers.minus("ru.ttraum.kotsql.postgres") + listOf("ru.ttraum.kotsql.ktor", "ru.ttraum.kotsql.jvm", "ru.ttraum.kotsql.jdbc", "ru.ttraum.kotsql.r2dbc"),
            "kotsql-mysql" to drivers.minus("ru.ttraum.kotsql.mysql") + listOf("ru.ttraum.kotsql.ktor", "ru.ttraum.kotsql.jvm", "ru.ttraum.kotsql.jdbc", "ru.ttraum.kotsql.r2dbc"),
            "kotsql-sqlite" to drivers.minus("ru.ttraum.kotsql.sqlite") + networkOnly + listOf("ru.ttraum.kotsql.jvm", "ru.ttraum.kotsql.jdbc", "ru.ttraum.kotsql.r2dbc"),
            "kotsql-transport-ktor" to drivers + listOf("ru.ttraum.kotsql.jvm", "ru.ttraum.kotsql.jdbc", "ru.ttraum.kotsql.r2dbc"),
            "kotsql-jdbc" to drivers + listOf("ru.ttraum.kotsql.ktor", "ru.ttraum.kotsql.r2dbc"),
            "kotsql-r2dbc" to drivers + listOf("ru.ttraum.kotsql.ktor", "ru.ttraum.kotsql.jdbc"),
        )
        val violations = rules.flatMap { (module, forbidden) ->
            val pattern = Regex("""(^|[^\w.])(${forbidden.joinToString("|") { Regex.escape(it) }})\.""")
            File(root, "$module/src").walkTopDown()
                .filter { it.extension == "kt" && it.parentFile.path.contains("Main") }
                .flatMap { file ->
                    file.readLines().asSequence().mapIndexedNotNull { i, line ->
                        val code = line.trimStart()
                        val isComment = code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")
                        if (!isComment && pattern.containsMatchIn(line.substringBefore(" // "))) "${file.relativeTo(root)}:${i + 1}: $code" else null
                    }
                }.toList()
        }
        if (violations.isNotEmpty()) throw GradleException("Modules reach where they shouldn't:\n" + violations.joinToString("\n"))
    }
}

subprojects { tasks.matching { it.name == "check" }.configureEach { dependsOn(checkModuleBoundaries) } }
