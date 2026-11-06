plugins {
    java
    application
}

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }

application { mainClass = "org.openjdk.jmh.Main" }

dependencies {
    implementation(project(":ksql-jdbc"))
    implementation(project(":ksql-r2dbc"))
    implementation(project(":ksql-postgres"))
    implementation(libs.pgjdbc)
    implementation(libs.r2dbc.postgresql)
    // Netty's native epoll, which reactor-netty brings for x86_64 only. Without it r2dbc-postgresql would run on plain NIO
    // on ARM and be measured below its best.
    runtimeOnly("io.netty:netty-transport-native-epoll:4.1.112.Final:linux-aarch_64")
    implementation(libs.reactor.core)
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.processor)
}
