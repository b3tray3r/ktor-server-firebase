val kotlinversion = "2.1.0"
val ktorversion = "2.3.4"
val logbackversion = "1.2.11"
val serializationversion = "1.5.1"

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "com.example"
version = "0.0.1"

application {
    mainClass.set("com.example.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorversion")
    implementation("io.ktor:ktor-server-netty:$ktorversion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorversion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorversion")
    implementation("io.ktor:ktor-server-config-yaml:$ktorversion")
    implementation("io.ktor:ktor-server-status-pages:$ktorversion")
    implementation("io.ktor:ktor-server-call-logging:$ktorversion")
    implementation("io.ktor:ktor-server-cors:$ktorversion")
    implementation("io.ktor:ktor-client-websockets:2.3.4")
    implementation("org.java-websocket:Java-WebSocket:1.5.3")
    implementation("io.ktor:ktor-client-core:$ktorversion")
    implementation("io.ktor:ktor-client-cio:$ktorversion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorversion")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationversion")
    implementation("ch.qos.logback:logback-classic:$logbackversion")
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation("io.ktor:ktor-client-websockets:2.3.7")
    implementation("com.google.firebase:firebase-admin:9.2.0")

    testImplementation("io.ktor:ktor-server-test-host:$ktorversion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinversion")


    implementation("com.google.auth:google-auth-library-oauth2-http:1.23.0")


}

tasks {
    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveBaseName.set("app")
        archiveClassifier.set("")
        archiveVersion.set("")
        manifest {
            attributes(mapOf("Main-Class" to "com.example.ApplicationKt"))
        }
    }

    build {
        dependsOn(shadowJar)
    }
}
