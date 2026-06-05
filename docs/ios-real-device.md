# iOS Real Device Backends

Arbigent uses one `--os=ios` entry point for both booted simulators and paired real iPhones. Real devices still need extra host tooling, a paired unlocked device, and a valid signed XCTest runner for `iphoneos`, but users should not have to choose a separate real-device mode.

## Automatic iOS Device Selection

Use `--os=ios` for both iOS Simulator and real iPhone targets. Arbigent discovers paired real devices through `xcrun devicectl` and booted simulators through `simctl`, then exposes them as iOS devices. The real-device path uses XCTest by default because it gives Arbigent the same structured UI tree that Maestro receives from its iOS driver.

If exactly one paired real iPhone is connected, no real/simulator switch is required:

```bash
export ARBIGENT_IOS_XCTEST_APPLE_TEAM_ID=B6Y9D6S4KK

arbigent run task --os=ios "Your task"
```

`ARBIGENT_IOS_REAL_DEVICE_ID` is optional and only needed when multiple paired real iPhones are connected or when the CLI must target a specific iPhone. It accepts the physical hardware UDID. The discovery code also accepts the CoreDevice identifier when selecting a paired device from `devicectl`, but XCTest and `iproxy` are run with the hardware UDID.

`ARBIGENT_IOS_XCTEST_XCTESTRUN` is optional. If it is unset, Arbigent uses the `LocalXCTestInstaller` bundled by its Maestro dependency. Real-device XCTest runners must be signed for the target device, so Arbigent first looks for a valid local driver at `~/.maestro/maestro-iphoneos-driver-build/driver-iphoneos/Build/Products`. If it is missing, Arbigent builds one from the `ai.looktech:maestro-cli:2.6.0-looktech.2` bundled driver source using `ARBIGENT_IOS_XCTEST_APPLE_TEAM_ID` or `DEVELOPMENT_TEAM`.

Useful overrides:

- `ARBIGENT_IOS_XCTEST_DRIVER_PRODUCTS_DIR`: explicit `Build/Products` directory containing an `.xctestrun`.
- `ARBIGENT_IOS_XCTEST_BUILD_DRIVER=false`: disable automatic local driver build and use the Maven-bundled `driver-iphoneos` fallback. This fallback can fail on real devices if its embedded provisioning profile does not include the phone.

Runtime notes:

- The iPhone must be unlocked and trusted before `xcodebuild test-without-building` starts.
- Arbigent starts `iproxy --udid <device-id> <port>:<port>` automatically unless `ARBIGENT_IOS_XCTEST_AUTO_IPROXY=false`.
- `MAESTRO_DRIVER_STARTUP_TIMEOUT` controls how long Arbigent waits for the XCTest HTTP channel, in milliseconds.
- iPhone Mirroring does not satisfy Xcode's locked-device preflight. It can show or control the phone visually, but Xcode can still report `Unlock <device> to Continue`.
- If tap/input fails with `Connection reset`, inspect `~/Library/Logs/maestro/xctest_runner_logs`. A reset can mean the XCTest runner crashed after receiving the HTTP request. On iPhone 12 mini with Apple Music, this was caused by `ScreenSizeHelper.orientationAwarePoint` hitting `Fatal error: Not implemented yet` when `XCUIDevice.shared.orientation` reported `.faceUp`; normalizing `.unknown`, `.faceUp`, and `.faceDown` to portrait in the runner fixed pure XCTest input.
- If the first `xcodebuild test-without-building` run fails with `Timed out while enabling automation mode`, confirm the device is awake and unlocked, then retry. On iPhone 12 mini the second run reused the signed driver products and started the XCTest HTTP server successfully.

Smoke test:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
ARBIGENT_IOS_REAL_XCTEST_SMOKE=1 \
ARBIGENT_IOS_REAL_DEVICE_ID=00008101-001D29020E42001E \
ARBIGENT_IOS_XCTEST_APPLE_TEAM_ID=B6Y9D6S4KK \
MAESTRO_DRIVER_STARTUP_TIMEOUT=180000 \
./gradlew :arbigent-core:test --tests io.github.takahirom.arbigent.IosRealXCTestDeviceTest.real\ device\ XCTest\ smoke
```

Apple Music real-device smoke verified on iPhone 12 mini:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
ARBIGENT_IOS_REAL_DEVICE_ID=00008101-001D29020E42001E \
ARBIGENT_IOS_XCTEST_APPLE_TEAM_ID=B6Y9D6S4KK \
MAESTRO_DRIVER_STARTUP_TIMEOUT=180000 \
arbigent-cli/build/install/arbigent/bin/arbigent run task \
  --os=ios \
  --ai-type=codex \
  --max-step=20 \
  --max-retry=1 \
  --log-level=debug \
  --log-file=/tmp/arbigent-apple-music-ado.log \
  --codex-timeout-ms=600000 \
  "In Apple Music, play Ado's top songs second song"
```

Expected result: `arbigent-result/result.yml` reports `isSuccess: true`, with the final step showing Ado's second top song playing in Apple Music.

## Internal Mirror Experiment

The mirror backend is kept as an internal experiment for visual control through Mirroir MCP. It is not the delivered iOS real-device backend because it does not expose the XCTest accessibility tree.

```bash
export ARBIGENT_IOS_REAL_BACKEND=mirror
export ARBIGENT_IOS_REAL_DEVICE_ID=00008101-001D29020E42001E
export ARBIGENT_IOS_MIRROR_MCP_COMMAND=/path/to/mirroir-mcp
```

Do not use mirror as proof that the XCTest-backed Arbigent flow is working. It exercises a different observation model and does not validate view-tree retrieval.
