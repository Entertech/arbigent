# Testing

Source builds resolve `ai.looktech:maestro-*` from GitHub Packages, which needs a token even for
public packages: `gpr.user` / `gpr.key` (PAT classic, `read:packages`) in `~/.gradle/gradle.properties`,
or `GITHUB_PACKAGES_USER` / `GITHUB_PACKAGES_TOKEN`. Without them only versions also on Maven Central
resolve; a stale token fails the build (no fall-through on 401/403).

```
./gradlew installDist
./arbigent-cli/build/install/arbigent/bin/arbigent --help
# no need to set --project-file, it is set in the .arbigent/settings.local.yml file
./arbigent-cli/build/install/arbigent/bin/arbigent run --scenario-ids="open-model-page"
```