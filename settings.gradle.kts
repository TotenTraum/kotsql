rootProject.name = "ksql"

pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }

include("ksql-core", "ksql-postgres", "ksql-mysql", "ksql-transport-ktor", "ksql-jdbc", "ksql-r2dbc", "ksql-benchmark", "ksql-benchmark-client", "ksql-compat-tests")
