# Distributing arbigent (GitHub Release + Homebrew)

**arbigent does NOT need to be recompiled per machine.** `arbigent-cli` is a Gradle
`application` project, so it builds a **self-contained** distribution that bundles
`bin/arbigent` + every `lib/*.jar` (including the `ai.looktech:maestro-*` fork jars).
At runtime it needs only a JDK — no Gradle, no Maven Local, no source.

## The release pipeline already exists

`.github/workflows/build-cli.yaml` (job `publish-cli`) is the upstream release flow,
already in this fork. On a **git tag push** it:

1. `./gradlew arbigent-cli:assemble` → produces `arbigent-<version>.tar.gz` + `.zip`
   **and** their `.sha256`/`.md5` (the checksum tasks in `arbigent-cli/build.gradle.kts`)
   into `arbigent-cli/build/distributions/`.
2. `softprops/action-gh-release` attaches those files to the GitHub Release for the tag.
3. `mislav/bump-homebrew-formula-action` updates the Homebrew formula `arbigent`
   (new `url` + `sha256`) in the tap.

So `brew install` was always the intended distribution channel — it was just hardwired
to `takahirom`. This fork re-points it to **Entertech** (download-url → `Entertech/arbigent`,
homebrew-tap → `Entertech/homebrew-tap`).

## One-time setup (done)

1. **Tap repo** `Entertech/homebrew-tap` — created as a **private** repo. The formula
   (`Formula/arbigent.rb`) lives there, including the auth design below. The bump-action
   *updates* an existing formula, so this had to exist before the first release.
2. **Repo secret `COMMITER_TOKEN`** on `Entertech/arbigent` — a PAT with write access to
   the tap repo so the action can push the formula bump. NOTE: the secret is spelled
   with a single T (`COMMITER_TOKEN`); the workflow maps it onto the action's
   `COMMITTER_TOKEN` env var.
3. **Maestro fork on Maven Central** (`ai.looktech:maestro-* 2.6.1-looktech.1`)
   — required so CI can resolve the dependency without your local `~/.m2`.
4. (Optional) `GRADLE_ENCRYPTION_KEY` secret for the Gradle build-cache action.

## Private-tap auth (how users install with no token setup)

Two auth surfaces, solved separately:

- **Tapping the private repo** — plain git over SSH; every engineer's existing SSH key works:
  `brew tap entertech/tap git@github.com:Entertech/homebrew-tap.git`
- **Downloading the release asset** — SSH does not help here (release assets are HTTP, and
  `browser_download_url` on private repos 404s even with a token). The formula uses a custom
  `GhReleaseDownloadStrategy` that shells out to `gh release download`, reusing the
  `gh auth login` engineers already have. This also works fine while `Entertech/arbigent`
  is public, so the tap keeps working either way.

## Cutting a release

```bash
# arbigent version comes from the git tag (palantir git-version),
# so the tarball is named arbigent-<tag>.tar.gz — matching the formula URL.
git tag 0.74.0-looktech.0
git push origin 0.74.0-looktech.0
```

**Fork caveat:** event-triggered workflows (push/tag) do NOT fire on this fork until an
org admin enables workflows from the repo's Actions tab ("I understand my workflows…"
button on https://github.com/Entertech/arbigent/actions). Until then, trigger the release
manually — `workflow_dispatch` works regardless:

```bash
gh workflow run publish-cli -R Entertech/arbigent --ref 0.74.0-looktech.0
```

The run attaches the tarball to the GitHub Release and pushes the formula bump
(url + sha256) to the tap. Then users install with:

```bash
brew tap entertech/tap git@github.com:Entertech/homebrew-tap.git
brew install entertech/tap/arbigent   # needs `gh auth login` once
```

## Runtime prerequisites for users (not bundled)

arbigent drives REAL devices, so the user's machine still needs the device toolchains —
these are NOT in the jar and can't be:

- **Android**: `adb` (Android platform-tools). Can be a brew `depends_on "android-platform-tools"`.
- **iOS**: Xcode (XCTest). Inherent system prerequisite; cannot be vendored.
- The JDK is handled by the formula (`depends_on "openjdk@17"`).

## Why not a native (GraalVM) single binary

arbigent + Maestro rely heavily on reflection, gRPC, and Compose — GraalVM native-image is
impractical here. The JVM tarball + `brew`-managed JDK is the right distribution model.
