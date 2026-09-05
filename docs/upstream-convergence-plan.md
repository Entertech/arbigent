# Upstream 收敛（hybrid-grounding ↔ takahirom/arbigent main）

状态（2026-09-05）：**已合并** upstream/main `be2a55c`（0.80.0，PR #386–#423）到 hybrid-grounding，合并提交 `621f9cc`。
Maestro 依赖同步升到 `ai.looktech:maestro-* 2.10.0-looktech.0`（fork main `fb786410`，上游 2.10.0 + 我方 3 个 iOS 补丁）。
下一次合并的冲突面已经很小：只剩本文第 2 节列出的几个"有意保留我方实现"的文件。

## 1. 合并时拍板的决策（及理由）

| 领域 | 决策 | 理由 |
|---|---|---|
| Maestro 供应链 | **保留 Maven Central 线**（`gradle/libs.versions.toml` 的 `ai.looktech` 坐标）。上游的 `gradle/maestro.gradle.kts`（下载官方 maestro.zip + 源码 tar）留在树里但根 `build.gradle.kts` 不 apply；`arbigent-core` 的 `BuildConfig.MAESTRO_VERSION` 改读 version catalog | 我方 3 个 Maestro 补丁（iOS backPress 滑动、settle 超时可配、朝向回退）只在 fork 里，官方 zip 没有 |
| iOS 真机 | **继续走我方 `IosRealXCTestDevice` 路径**（DeviceFinder 只实例化 `IOSRealXCTest` / `IOSRealMirror`）。上游的 `IosReal` / `IosRealDriverProducts` / `ArbigentDevicectlIOSDevice`（上游版）留在树里但不被实例化（dormant，其 32 个单测照常跑） | 我方路径在 12 mini / 13 Pro 上反复验证过；上游 `IosRealDriverProducts` 依赖 maestro.gradle.kts 打进资源的 runner 源码，我们没启用。删掉上游文件会让下次合并变成 modify/delete 冲突 |
| iproxy 转发 | **采纳上游** `IosRealXCTestPortForwarder.kt`（ownership pidfile、孤儿回收、端口占用诊断），我方内嵌在 ArbigentDeviceOs.kt 的版本删除 | 残留 iproxy 占 22087 是我们实际踩过的坑，上游系统性解决 |
| Team ID 检测 | **采纳上游** OU 证书解析（`security find-certificate` + openssl），在其上补两个我方兼容函数 `autoDetectTeamId()` / `detectedTeamsMessage()`（0/多团队返回 null 而不抛，多团队结果缓存只警告一次） | 我方旧实现抓 CN 括号，对个人开发者证书是错的 |
| CLI 设备选择 | **保留** `--device` + `arbigent devices`；上游的 `--ios-xctest-apple-team-id` / `--ios-real-device-id` / `--ios-real-device-port` 不暴露，对应的两个 CliTest 用例删除 | 测试同学的文档和习惯已经建立在 `--device` 上；team id 走 env / settings |
| `fetchAvailableDevicesByOs` | 参数取并集：我方 `requestedDeviceId / includeUnconnectable / honorEnvironmentPins` + 上游 `includeAllIosDevices / iosConfig`。UI 传的 `includeAllIosDevices=true` 映射为 includeUnconnectable；`iosConfig.deviceId` 作为 requestedDeviceId 的回退 | UI 和 CLI 两个调用方都不用改 |
| 决策缓存 | 我方 fuzzy 回退移植进上游新抽出的 `ArbigentDecisionCacheInterceptor`；`cacheHit` 标志按上游改为 `stepSource`（Ai / Cache / Replay），ExecutionSummary 统计非 Ai 步 | 上游的 replayWithFallback 是同一思路的完整版，见第 3 节 |
| MaestroDevice | 采纳上游的 `Connection`（maestro + orchestra + onClose 原子换入、`closeConnection` 幂等、`resolveScreenshotFile` 路径收敛）；我方层级缓存、前台应用提示、`elementNotFound()`、PERF 日志叠在其上；重连后清缓存 | 上游修的是 reconnect 泄漏 forwarder 的真问题 |
| Anthropic provider | 采纳（新模块 `arbigent-ai-anthropic`），用 `AnthropicAiProvider` 包进我方 provider 边界；其动作解析补齐我方专有动作（GoHome / LaunchApp / Drag / Swipe）并把 ClickAtCoordinates 改成百分比坐标 | 不补的话 Claude 一选这些动作就抛 Unsupported |
| 命名冲突 | 我方 `ArbigentDevicectlIOSDevice`（包装 DeviceControlIOSDevice、实现 clearAppState/openUrl）改名 `IosRealXCTestDeviceController`，上游同名类保留 | 同包同名无法共存 |
| 目录名 | XCTest runner 日志目录保持 `arbigent-result/maestro-xctest-logs/`（上游是 `xctest-logs/`） | quickstart 里的排障说明引用了它 |

## 2. 下次合并时仍会冲突的文件（我方有意分叉）

- `arbigent-core/build.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml`、`sample-test/build.gradle.kts`（Maven 供应链）
- `DeviceFinder.kt`（iOS 分支走我方目录）、`ArbigentDeviceOs.kt`（id/description + 我方两个 iOS 类）
- `CommonOptions.kt` / `RunCommand.kt` / `RunTaskCommand.kt` / `main.kt`（`--device`、codex provider、devices 子命令）
- `AiConfig.kt`（codex 配置）、`README.md`（CLI 选项表）

其余上游文件（replay、resolver、graph/guide、Anthropic 模块本体、iOS 上游实现）我方未改动，应能自动合并。

## 3. 后续待办（按价值排序）

1. **replayWithFallback 上线到 QA 回归套件**：项目级开关 `settings.cacheStrategy.replayWithFallback`，前提是场景配 `imageAssertions`。上线后废弃我方 `DecisionCacheFuzzy`（同一思路的弱化版）。
2. **iOS 真机实现二选一**：要切到上游路径，需要把 `gradle/maestro.gradle.kts` 的源码 tar 指向 Entertech/Maestro 的 tag（带 ScreenSizeHelper 补丁）并 apply 其 `maestroIosDriverSource` 部分；收益是上游的 profile 过期检测、跨进程构建锁。当前没有必要。
3. Token 用量文件（`arbigent-result/usages/*.json`）配价格表做按次算账脚本（quickstart 已有 jq 版）。
4. Anthropic 档位实测（Haiku 4.5 作为难任务候选），未测。

## 4. Rejected alternatives

- 以上游为基底重移植我方 73 个提交：同样的结果，但要对着陌生结构逐个解 25 次冲突。
- 直接用 mobile-dev-inc 官方 maestro.zip：丢掉 backPress / settle-timeout / 朝向三个补丁。
- 删除上游 dormant 的 iOS 文件：下次合并变成 modify/delete 冲突，且丢掉 32 个单测的覆盖。

## 5. 验证证据（2026-09-05）

- 单元测试：arbigent-core 278、arbigent-cli 72、arbigent-ai-openai 41、arbigent-ai-anthropic 40，全部 0 失败。
- CLI：`arbigent --help` 列出 run / scenarios / tags / devices / graph / instruction / guide；`arbigent devices` 正常列出 Android + iOS 真机。
- 真机冒烟：见本文件末尾"冒烟记录"。
