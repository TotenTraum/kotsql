import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.publish) apply false
}

allprojects {
    group = providers.gradleProperty("buildInfo.group").get()
    version = providers.gradleProperty("buildInfo.version").get()
}

/** The modules that are published: their public API is declared on purpose and checked against a dump of it. */
val libraries = setOf(
    "kotsql-core", "kotsql-transport", "kotsql-jvm-values", "kotsql-postgres", "kotsql-mysql", "kotsql-sqlite",
    "kotsql-transport-ktor", "kotsql-jdbc", "kotsql-r2dbc",
)

subprojects {
    if (name in libraries) {
        plugins.withId("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
                explicitApi()
                // kotsql's modules share what InternalKotsqlApi marks; it stays out of the checked dumps.
                compilerOptions { optIn.add("ru.ttraum.kotsql.InternalKotsqlApi") }
                @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
                abiValidation {
                    filters { exclude { annotatedWith.add("ru.ttraum.kotsql.InternalKotsqlApi") } }
                }
            }
        }
    }
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

// Maven Central publishing for every module that applies the publish plugin, which are the libraries.
//   ./gradlew publishAllToMavenLocal      — local check, no credentials needed
//   ./gradlew publishAllToMavenCentral    — needs credentials in ~/.gradle/gradle.properties
// Apple targets build only on macOS, so publish from a Mac to get every target in one release.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        val projectUrl = providers.gradleProperty("buildInfo.url").get()

        extensions.configure<MavenPublishBaseExtension> {
            coordinates(group.toString(), project.name, version.toString())

            pom {
                name.set(project.name)
                description.set(providers.provider { project.description })
                url.set(projectUrl)

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                scm {
                    url.set(projectUrl)
                    connection.set("scm:git:git://${projectUrl.removePrefix("https://")}.git")
                    developerConnection.set("scm:git:ssh://git@${projectUrl.removePrefix("https://")}.git")
                }

                developers {
                    developer {
                        id.set("ttraum")
                        name.set("Azim Usmanov")
                        url.set("https://github.com/ttraum/")
                    }
                }
            }

            plugins.withId("org.jetbrains.kotlin.multiplatform") {
                configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
            }

            publishToMavenCentral()

            // Sign only when a key is configured, so publishToMavenLocal works on any machine.
            // Maven Central rejects unsigned artifacts, so a release still requires the key.
            val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
                providers.gradleProperty("signing.keyId").isPresent
            if (hasSigningKey) signAllPublications()
        }
    }
}

tasks.register("publishAllToMavenLocal") {
    group = "publishing"
    description = "Publishes every library to the local Maven repository."
    dependsOn(libraries.map { ":$it:publishToMavenLocal" })
}
tasks.register("publishAllToMavenCentral") {
    group = "publishing"
    description = "Publishes and releases every library to Maven Central."
    dependsOn(libraries.map { ":$it:publishAndReleaseToMavenCentral" })
}
