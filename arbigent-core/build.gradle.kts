plugins {
  id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin
  id("org.jetbrains.kotlin.plugin.serialization") version libs.versions.kotlin
  id("com.javiersc.semver") version "0.8.0"
  alias(libs.plugins.buildconfig)
}

semver {
  isEnabled.set(true)
  tagPrefix.set("")
}

kotlin {
  explicitApi()
  java.toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
  sourceSets {
    all {
      languageSettings.optIn("io.github.takahirom.arbigent.ArbigentInternalApi")
    }
  }
}

buildConfig {
  packageName("io.github.takahirom.arbigent")
  buildConfigField("VERSION_NAME", version.toString())
  buildConfigField("MAESTRO_VERSION", libs.versions.maestro.get())
  useKotlinOutput { internalVisibility = false }
}

// This fork consumes Maestro from Maven Central (ai.looktech:maestro-*, published from
// Entertech/Maestro with the iOS backPress / settle-timeout / orientation patches) instead of
// upstream's pinned maestro.zip pipeline (gradle/maestro.gradle.kts, kept for reference only).
// Upstream's IosRealDriverProducts (runner source packaged as an ios-real-driver/ resource) is
// therefore dormant here; physical iPhones go through the fork's IosRealXCTestDevice path.

dependencies {
  implementation(project(":arbigent-core-web-report"))
  api(libs.maestro.orchestra)
  implementation(libs.maestro.cli)
  api(libs.maestro.client)
  implementation("dev.mobile:dadb:1.2.9")
  api(libs.maestro.ios)
  api(libs.maestro.ios.driver)

  api(project(":arbigent-core-model"))
  api(project(":arbigent-mcp-client"))
  implementation("com.charleskorn.kaml:kaml:0.83.0")
  api("org.mobilenativefoundation.store:cache5:5.1.0-alpha05")
  api("com.mayakapps.kache:file-kache:2.1.1")

  // To expose requestBuilderModifier
  api(libs.ktor.client.core)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.ktor.serialization.json)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.logging)
  implementation(libs.ktor.client.contentnegotiation)
  implementation(libs.kotlinx.io.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.identity.jvm)
  implementation(project(":arbigent-core-model"))
  implementation("io.github.darkxanter:webp-imageio:0.3.3")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

  implementation("co.touchlab:kermit:2.0.4")
  testImplementation(kotlin("test"))
  // coroutine test
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.5.2")
  // robospec
  testImplementation("io.github.takahirom.robospec:robospec:0.2.0")
}
