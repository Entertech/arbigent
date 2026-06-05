# iOS Real Device Backends

Arbigent keeps the existing iOS Simulator path as the default iOS backend. Real iOS devices are opt-in because they need extra host tooling, a paired unlocked device, and a valid XCTest runner for `iphoneos`.

## XCTest Backend

Use the XCTest backend when the test needs the real iOS accessibility/view hierarchy. This is the preferred path for semantic agent execution because it gives Arbigent the same structured UI tree that Maestro receives from its iOS driver.

```bash
export ARBIGENT_IOS_DEVICE_KIND=real
export ARBIGENT_IOS_REAL_BACKEND=xctest
export ARBIGENT_IOS_REAL_DEVICE_ID=00008101-001D29020E42001E
export ARBIGENT_IOS_XCTEST_APPLE_TEAM_ID=B6Y9D6S4KK
# Optional override when testing a locally rebuilt XCTest runner:
# export ARBIGENT_IOS_XCTEST_XCTESTRUN=/path/to/Build/Products/maestro-driver-ios_iphoneos26.5-arm64.xctestrun
```

`ARBIGENT_IOS_REAL_DEVICE_ID` accepts the physical hardware UDID. The discovery code also accepts the CoreDevice identifier when selecting a paired device from `devicectl`, but XCTest and `iproxy` are run with the hardware UDID.

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
ARBIGENT_IOS_DEVICE_KIND=real \
ARBIGENT_IOS_REAL_BACKEND=xctest \
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

## Mirror Backend

The mirror backend is an explicit fallback for visual control of a real iPhone through Mirroir MCP:

```bash
export ARBIGENT_IOS_DEVICE_KIND=real
export ARBIGENT_IOS_REAL_BACKEND=mirror
export ARBIGENT_IOS_REAL_DEVICE_ID=00008101-001D29020E42001E
export ARBIGENT_IOS_MIRROR_MCP_COMMAND=/path/to/mirroir-mcp
```

This backend can launch apps, take screenshots, tap, type, swipe, and synthesize OCR-derived elements. It does not expose the XCTest accessibility tree, so it is not equivalent to the semantic XCTest backend.

Use mirror when:

- XCTest cannot be used for a specific exploratory flow.
- Visual control is enough for the test.
- The flow must interact with an app or system screen that XCTest cannot inspect.

Do not use mirror as proof that the XCTest-backed Arbigent flow is working. It exercises a different observation model and does not validate view-tree retrieval.
