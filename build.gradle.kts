plugins {
    kotlin("jvm") version "2.2.0"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val vertxVersion = "4.5.8"

dependencies {
    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("io.vertx:vertx-web:$vertxVersion")
    implementation("io.vertx:vertx-web-client:$vertxVersion")
    implementation("io.vertx:vertx-sql-client:$vertxVersion")
    implementation("io.vertx:vertx-mssql-client:$vertxVersion")
    implementation("io.vertx:vertx-jdbc-client:$vertxVersion")
    implementation("io.agroal:agroal-pool:1.13")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
    implementation("com.microsoft.sqlserver:mssql-jdbc:12.6.1.jre11")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("MainKt")
}
