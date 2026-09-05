import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin
}
val localProperties = Properties()
if (rootProject.file("local.properties").exists()) {
    localProperties.load(rootProject.file("local.properties").inputStream())
}

tasks.withType<Test> {
    useJUnitPlatform()
    this.systemProperty("OPENAI_API_KEY", localProperties.getProperty("OPENAI_API_KEY"))
}

dependencies {
    implementation(project(":arbigent-core"))
    implementation(project(":arbigent-ai-openai"))
    // maestro client (fork: Maven Central ai.looktech coordinates, see gradle/libs.versions.toml)
    api(libs.maestro.client)
    api(libs.maestro.orchestra)
    testImplementation(kotlin("test"))
    // coroutine test
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.5.2")
}

