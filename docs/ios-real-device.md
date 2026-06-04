# iOS Real Device Backends

Arbigent keeps the existing iOS Simulator path as the default iOS backend. Real iOS devices are opt-in because they need extra host tooling, a paired unlocked device, and a valid XCTest runner for `iphoneos`.

## XCTest Backend

Use the XCTest backend when the test needs the real iOS accessibility/view hierarchy. This is the preferred path for semantic agent execution because it gives Arbigent the same structured UI tree that Maestro receives from its iOS driver.

```bash
export ARBIGENT_IOS_DEVICE_KIND=real
export ARBIGENT_IOS_REAL_BACKEND=xctest
export ARBIGENT_IOS_REAL_DEVICE_ID=00008101-001D29020E42001E
export ARBIGENT_IOS_XCTEST_XCTESTRUN=/path/to/Build/Products/maestro-driver-ios_iphoneos26.5-arm64.xctestrun
```

`ARBIGENT_IOS_REAL_DEVICE_ID` accepts the physical hardware UDID. The discovery code also accepts the CoreDevice identifier when selecting a paired device from `devicectl`, but XCTest and `iproxy` are run with the hardware UDID.

`ARBIGENT_IOS_XCTEST_XCTESTRUN` is optional. If it is unset, Arbigent uses the `LocalXCTestInstaller` bundled by its Maestro dependency. Maestro 1.40.0 only packages the simulator runner in its published artifact, so real devices need an externally built `iphoneos` `.xctestrun` until the Maestro dependency ships `driver-iphoneos` resources.

Runtime notes:

- The iPhone must be unlocked and trusted before `xcodebuild test-without-building` starts.
- Arbigent starts `iproxy --udid <device-id> <port>:<port>` automatically unless `ARBIGENT_IOS_XCTEST_AUTO_IPROXY=false`.
- `MAESTRO_DRIVER_STARTUP_TIMEOUT` controls how long Arbigent waits for the XCTest HTTP channel.
- iPhone Mirroring does not satisfy Xcode's locked-device preflight. It can show or control the phone visually, but Xcode can still report `Unlock <device> to Continue`.

Smoke test:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
ARBIGENT_IOS_REAL_XCTEST_SMOKE=1 \
ARBIGENT_IOS_REAL_DEVICE_ID=00008101-001D29020E42001E \
ARBIGENT_IOS_XCTEST_XCTESTRUN=/path/to/Build/Products/maestro-driver-ios_iphoneos26.5-arm64.xctestrun \
MAESTRO_DRIVER_STARTUP_TIMEOUT=180 \
./gradlew :arbigent-core:test --tests io.github.takahirom.arbigent.IosRealXCTestDeviceTest.real\ device\ XCTest\ smoke
```

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
