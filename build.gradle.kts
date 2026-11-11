plugins {
    kotlin("jvm") version "2.4.0"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.statbot"
version = "2.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Корутины: асинхронная обработка апдейтов, планировщик, запросы к ИИ
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // База данных (SQLite через Exposed)
    implementation("org.jetbrains.exposed:exposed-core:0.41.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.41.1")
    implementation("org.jetbrains.exposed:exposed-java-time:0.41.1")
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")

    // JSON и логирование. HTTP — встроенный java.net.http, отдельный клиент не нужен.
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<JavaExec> {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    workingDir = rootDir
}

// Один исполняемый jar: build/libs/statbot.jar  →  java -jar statbot.jar
tasks.shadowJar {
    archiveFileName.set("statbot.jar")
    manifest { attributes["Main-Class"] = "com.statbot.bot.MainKt" }
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
