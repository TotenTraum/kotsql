rootProject.name = "kotsql"

pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // SQLite for the JVM and native targets alike comes from here.
        google { content { includeGroupByRegex("androidx\\..*") } }
    }
}

include("kotsql-core", "kotsql-transport", "kotsql-jvm-values", "kotsql-postgres", "kotsql-mysql", "kotsql-sqlite", "kotsql-transport-ktor", "kotsql-jdbc", "kotsql-r2dbc", "kotsql-benchmark", "kotsql-benchmark-client", "kotsql-compat-tests")
