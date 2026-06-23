plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.telegram.bot)
    ksp(libs.ktnip)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.db.scheduler)
    implementation(libs.h2)
    implementation(libs.hikari)
    implementation(libs.slf4j.simple)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation("io.ktor:ktor-client-mock:3.5.0")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("cfpbot.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
