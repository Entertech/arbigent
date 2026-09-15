// GitHub Packages credentials for the looktech Maestro fork (see dependencyResolutionManagement).
fun githubPackagesCredential(propertyName: String, vararg environmentNames: String): String? {
    return providers.gradleProperty(propertyName).orNull
        ?.takeIf { it.isNotBlank() }
        ?: environmentNames.firstNotNullOfOrNull { name ->
            providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        }
}

// Registers https://maven.pkg.github.com/Entertech/<repository> for the given groups. GitHub
// Packages requires a token even for public packages, so the repository is only registered when
// credentials are present; otherwise resolution falls through to the repositories below.
fun RepositoryHandler.githubPackagesRepository(
    repository: String,
    repositoryName: String,
    vararg groups: String,
) {
    val githubPackagesUser = githubPackagesCredential("gpr.user", "GITHUB_PACKAGES_USER", "USERNAME")
    val githubPackagesToken = githubPackagesCredential("gpr.key", "GITHUB_PACKAGES_TOKEN", "TOKEN")
    if (githubPackagesUser == null || githubPackagesToken == null) {
        logger.warn(
            "GitHub Packages repository $repositoryName skipped: set gpr.user / gpr.key in " +
                "~/.gradle/gradle.properties (PAT classic with read:packages) or " +
                "GITHUB_PACKAGES_USER / GITHUB_PACKAGES_TOKEN. ${groups.joinToString()} will only " +
                "resolve from Maven Central / Maven Local."
        )
        return
    }

    maven {
        name = repositoryName
        url = uri("https://maven.pkg.github.com/Entertech/$repository")
        credentials {
            username = githubPackagesUser
            password = githubPackagesToken
        }
        content {
            groups.forEach(::includeGroup)
        }
    }
}

pluginManagement {
    repositories {
        maven("https://redirector.kotlinlang.org/maven/compose-dev")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        kotlin("jvm").version(extra["kotlin.version"] as String)
        id("org.jetbrains.compose").version(extra["compose.version"] as String)
        id("org.jetbrains.kotlin.plugin.compose").version(extra["kotlin.version"] as String)
    }
}
plugins {
    // Auto-provisions the JDK 21 toolchain required by arbigent-ui (Jewel) on CI.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        // Local publishToMavenLocal builds of the Maestro fork win over every remote repository.
        mavenLocal()
        // The looktech Maestro fork (ai.looktech:maestro-*) is published to GitHub Packages from
        // Entertech/Maestro (workflow publish-github-packages.yaml). Credentials: gpr.user / gpr.key
        // in ~/.gradle/gradle.properties or GITHUB_PACKAGES_USER / GITHUB_PACKAGES_TOKEN (CI passes
        // GITHUB_TOKEN). Without credentials this repository is skipped and only versions that were
        // also released to Maven Central resolve. A stale token fails the build outright: Gradle does
        // not fall through on 401 / 403, only on 404.
        githubPackagesRepository(
            repository = "Maestro",
            repositoryName = "GitHubPackagesMaestro",
            "ai.looktech",
        )
        google()
        mavenCentral()
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://www.jetbrains.com/intellij-repository/snapshots")
        maven("https://packages.jetbrains.team/maven/p/kpm/public/")
    }
}

rootProject.name = "arbigent"
include(":arbigent-core-model")
include(":arbigent-core")
include(":arbigent-mcp-client")
include(":arbigent-device-maestro")
include(":arbigent-ai-openai")
include(":arbigent-ai-anthropic")
include(":arbigent-cli")
include(":arbigent-ui")
include(":arbigent-core-web-report")
include(":sample-test")
