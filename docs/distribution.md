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

## One-time setup to make fork releases work

1. **Create the tap repo** `Entertech/homebrew-tap` with an initial `Formula/arbigent.rb`
   (see below). The bump-action *updates* an existing formula; it must exist first.
   Easiest correct base: copy upstream's proven formula from
   `https://github.com/takahirom/homebrew-repo/blob/main/Formula/arbigent.rb` and change
   the repo/url to Entertech.
2. **Set repo secret `COMMITTER_TOKEN`** on `Entertech/arbigent` — a PAT (classic: `repo`,
   or fine-grained with Contents:write on the tap repo) so the action can push the formula
   bump to `Entertech/homebrew-tap`.
3. (Already done) **Maestro fork on Maven Central** (`ai.looktech:maestro-* 2.6.1-looktech.0`)
   — required so CI can resolve the dependency without your local `~/.m2`.
4. (Optional) `GRADLE_ENCRYPTION_KEY` secret for the Gradle build-cache action.

## Cutting a release

```bash
# arbigent version comes from the git tag (palantir git-version).
git tag 0.74.0-looktech.0
git push origin 0.74.0-looktech.0
# build-cli.yaml fires on the tag -> Release assets + Homebrew formula bump
```
Then: `brew install entertech/tap/arbigent`.

## Starter formula (`Entertech/homebrew-tap` → `Formula/arbigent.rb`)

The bump-action overwrites `url`+`sha256` each release; the rest is the install logic.
Verify the install block against the actual tarball layout (Gradle distTar extracts to
`arbigent-<ver>/{bin,lib}`; Homebrew enters the single root dir, so `Dir["*"]` = `bin`,`lib`).

```ruby
class Arbigent < Formula
  desc "AI-powered cross-platform (Android & iOS) mobile UI test agent — Looktech fork"
  homepage "https://github.com/Entertech/arbigent"
  url "https://github.com/Entertech/arbigent/releases/download/0.74.0-looktech.0/arbigent-0.74.0-looktech.0.tar.gz"
  sha256 "REPLACE_WITH_TARBALL_SHA256"  # bump-homebrew-formula-action overwrites this
  license "Apache-2.0"

  depends_on "openjdk@17"

  def install
    libexec.install Dir["*"]
    (bin/"arbigent").write_env_script libexec/"bin/arbigent",
      JAVA_HOME: Formula["openjdk@17"].opt_prefix
  end

  test do
    assert_match "arbigent", shell_output("#{bin}/arbigent --help 2>&1", 2)
  end
end
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
