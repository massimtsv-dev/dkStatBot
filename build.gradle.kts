plugins {
    kotlin("jvm") version "2.4.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.kotlin-telegram-bot:kotlin-telegram-bot:6.0.7")
    implementation("org.jetbrains.exposed:exposed-core:0.41.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.41.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.41.1")
    implementation("org.jetbrains.exposed:exposed-java-time:0.41.1")
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")
    implementation("org.knowm.xchart:xchart:3.8.4")
    implementation("org.slf4j:slf4j-simple:1.7.36")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.michael-bull.kotlin-result:kotlin-result:1.1.18")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaExec> {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dconsole.encoding=UTF-8")
}