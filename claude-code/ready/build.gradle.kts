plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.bytedeco:javacv-platform:1.5.13")
    implementation("io.ktor:ktor-client-core:3.4.3")
    implementation("io.ktor:ktor-client-cio:3.4.3")
    implementation("io.ktor:ktor-server-core:3.4.3")
    implementation("io.ktor:ktor-server-cio:3.4.3")

    implementation(platform("ai.djl:bom:0.36.0"))
    implementation("ai.djl:api")
    implementation("ai.djl.pytorch:pytorch-engine")
    implementation("ai.djl.pytorch:pytorch-model-zoo")
    implementation("ai.djl.onnxruntime:onnxruntime-engine")

    // Koog — JetBrains Kotlin-native AI agent framework (Stage 4 sub-agent orchestration)
    implementation("ai.koog:koog-agents:0.7.3")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "Stage1Kt"
}

val stages = listOf("Stage1", "Stage2", "Stage3Vibecoding", "Stage3Fixed", "Stage4Vibecoding", "Stage4Fixed", "Stage4Live", "KoogHello")
stages.forEach { stage ->
    tasks.register<JavaExec>("run${stage}") {
        group = "application"
        description = "Run $stage"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass = "${stage}Kt"
        standardInput = System.`in`
    }
}
