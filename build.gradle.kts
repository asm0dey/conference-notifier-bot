plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.graalvm.native)
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
    // Default JDK 21; override with -PjdkVersion=25 so the Docker build can compile on the
    // NIK 25 container's own JDK (no second toolchain to provision).
    jvmToolchain((providers.gradleProperty("jdkVersion").orNull ?: "21").toInt())
}

application {
    mainClass.set("cfpbot.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

graalvmNative {
    // native-image runs from GRAALVM_HOME/JAVA_HOME (Liberica NIK); don't make the
    // plugin hunt for a GraalVM toolchain that would clash with jvmToolchain(21).
    toolchainDetection.set(false)
    // Pull reflection/resource/proxy metadata for H2, Ktor, HikariCP, etc.
    metadataRepository {
        enabled.set(true)
    }
    binaries {
        named("main") {
            imageName.set("cfpbot")
            mainClass.set("cfpbot.MainKt")
            // init-at-build-time set mirrored from the official ktor graalvm sample,
            // plus slf4j-simple (this project's logger).
            buildArgs.add("--initialize-at-build-time=kotlin")
            buildArgs.add("--initialize-at-build-time=io.ktor")
            buildArgs.add("--initialize-at-build-time=org.slf4j")
            buildArgs.add("--initialize-at-build-time=kotlinx.io")
            // Surgical, not the whole kotlinx.coroutines package — broad init would drag
            // dispatcher thread pools into the image heap (a worse error).
            buildArgs.add("--initialize-at-build-time=kotlinx.coroutines.CoroutineName")
            buildArgs.add("--initialize-at-build-time=kotlinx.coroutines.CoroutineName\$Key")
            // Ktor's NonceKt.<clinit> spins a background nonce-generator coroutine; force it to
            // run-time init so that live coroutine doesn't land in the image heap (known Ktor gotcha).
            buildArgs.add("--initialize-at-run-time=io.ktor.util.NonceKt")
            // telegram-bot holds serialization state in the heap at build time; init the whole
            // kotlinx.serialization tree at build time rather than chasing each nested object.
            buildArgs.add("--initialize-at-build-time=kotlinx.serialization")
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}
