plugins { java }

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }

// Real applications' stack against the drivers, kept apart so that its dependency versions don't touch anything else.
dependencies {
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-json")
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation(project(":kotsql-r2dbc"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":kotsql-jdbc"))
    testRuntimeOnly(project(":kotsql-postgres"))
    testRuntimeOnly(project(":kotsql-mysql"))
    testRuntimeOnly(project(":kotsql-sqlite"))
    testRuntimeOnly("org.hibernate.orm:hibernate-community-dialects")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }
