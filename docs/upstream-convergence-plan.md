# Upstream 收敛（hybrid-grounding ↔ takahirom/arbigent main）

状态（2026-09-15）：**已合并到 upstream 0.82.0**（`7151b801`，PR 至 0.82.0；合并提交 `f667504a`，此前 0.80.0 的合并是 `621f9cc`）。
Maestro 依赖为 `ai.looktech:maestro-* 2.10.0-looktech.0`，**自 09-15 起从 GitHub Packages 解析**（`maven.pkg.github.com/Entertech/Maestro`，
由 fork 的 `publish-github-packages.yaml` 发布；Central 上的同版本保留作回退）。用户拍板原话："我觉得换 github package 还方便你发版测试"。Maestro 上游 2.10.0 之后没有新发布，
截至 09-15 只有 7 个未发布提交（orchestra YAML schema 推导、start-device API 37、cloud 上传路径、文档），不涉及 arbigent 用到的 client / driver，故不另出 fork 版本。
下一次合并的冲突面已经很小：只剩本文第 2 节列出的几个"有意保留我方实现"的文件；0.82.0 这次只冲突了 `arbigent-cli/build.gradle.kts` 的一行依赖。

## 1. 合并时拍板的决策（及理由）

| 领域 | 决策 | 理由 |
|---|---|---|
| Maestro 供应链 | **Maven 坐标不变（`gradle/libs.versions.toml` 的 `ai.looktech`），仓库改为 GitHub Packages**：`settings.gradle.kts` 在 `mavenCentral()` 之前注册 `maven.pkg.github.com/Entertech/Maestro`（`includeGroup("ai.looktech")`，凭据 `gpr.user`/`gpr.key` 或 `GITHUB_PACKAGES_USER`/`GITHUB_PACKAGES_TOKEN`，缺凭据时跳过并 warn，`mavenLocal()` 仍最前）。Maestro fork 用 init script 注入仓库、`publish-github-packages.yaml` 手动 dispatch 发布，不改任何模块 build 文件；`publish-release.yaml`（Central，staging）原样保留作回退。上游的 `gradle/maestro.gradle.kts` 留在树里但根 `build.gradle.kts` 不 apply；`BuildConfig.MAESTRO_VERSION` 读 version catalog | 我方 3 个 Maestro 补丁（iOS backPress 滑动、settle 超时可配、朝向回退）只在 fork 里，官方 zip 没有。GitHub Packages 省掉 Sonatype staging 与手动放行，但拉包也要 token：brew 用户无感（tar.gz 自带 jar），源码构建与 CI 需要凭据；GitHub Packages 没有 staging，版本一发即见且不能重传，迭代靠升 `-looktech.N` |
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
- `settings.gradle.kts`（GitHub Packages 仓库 + 凭据辅助函数）、`.github/workflows/build-cli.yaml`（`packages: read` + token 传递，以及 fork 的 dispatch / tap 行）、`CLAUDE.md`（源码构建凭据说明）
- `arbigent-cli/build.gradle.kts`（依赖块：我方 anthropic / serialization 与上游新增依赖相邻，0.82.0 这次就冲突了一行）
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
- 用 Entertech/Maestro 的 GitHub Release maestro.zip 走上游 `maestro.gradle.kts`（免 token）：fork 要跑 jreleaser（`publish-cli.yaml` 的 mobile-dev-inc 门控、brew tap 副作用都得改），且上游 jar 列表没有 maestro-cli.jar，我方 `IosRealXCTestDriverProducts.kt` 依赖 `maestro.cli.driver`。比 GitHub Packages 多一圈机器，先不做。
- Maven Central 走 auto release：用户原话"触发然后 staging 不要 auto release"，Central 路径保留时仍按 staging。

## 5. 验证证据（2026-09-15，0.82.0 合并后的 CLI 构建）

- 单元测试：arbigent-core 318、arbigent-cli 107、arbigent-ai-openai 43、arbigent-ai-anthropic 44，全部 0 失败。
- CI 同款命令 `./gradlew arbigent-cli:assemble` 产出 tar.gz / zip 及 `.sha256` / `.md5`；`build-cli.yaml` 里的 fork 行（workflow_dispatch、COMMITER_TOKEN 映射、Entertech tap）核对无丢失。
- CLI：`arbigent --help` 列出 run / scenarios / tags / devices / graph / sort / instruction / guide / wrapper。

### GitHub Packages 切换验证（2026-09-15）

- Maestro fork `13b66f1a`：`publish-github-packages.yaml` run 34927410567 成功，10 个模块的 `2.10.0-looktech.0` 连签名一起上传，14 分钟。本地 `--dry-run` 事先确认每个模块都有 sign + publish 任务。
- arbigent 本地：`:arbigent-core:dependencies --configuration compileClasspath -Dmaven.repo.local=<空目录> --refresh-dependencies --info`，8 个 maestro 模块的 `.module` / `.pom` 全部来自 `maven.pkg.github.com/Entertech/Maestro`，Central 的 `ai/looktech` 零次下载。
- 无凭据路径：`-Pgpr.user= -Pgpr.key=` 时打出 skipped 警告并回落 Central（`-q` 会吞掉这条 warn）。
- 坑：Gradle 只对 404 回落，`gpr.key` 过期或无 `read:packages` 会直接 401/403 失败，不会回落到 Central。
- CI：`publish-cli` run 34933715131（main `6a4ad0be`）`build` job 成功，`GITHUB_TOKEN`（`packages: read`）跨仓库读 Entertech/Maestro 的包可用，无需 PAT secret。判定依据：settings 没有打 skipped 警告（仓库已注册），而无 `read:packages` 的 token 对该 URL 返回 401、Gradle 对 401 不回落，成功即说明拿到了 200。e2e 两个 job 照旧红（fork 无模拟器 / adb）。这套 CI 的 Gradle 输出不打印 Download 行，别拿它当证据。
- 匿名与可见性：Maven 类 package 只继承仓库可见性，Entertech/Maestro 是 public 但匿名 GET 仍 401（同文件在 Central 是 200）；GitHub 文档只对容器仓库开放匿名拉取。也就是说没有任何 GitHub 设置能免掉下载 token，只能免掉"手工建 PAT"：CI 用 `GITHUB_TOKEN`，本机可以让 `gh auth refresh -s read:packages` 后的 gh 登录代替 PAT（待拍板）。

### 冒烟记录（模型 qwen3.7-flash / DashScope，场景"打开设置→电池并确认电量显示"）

- **Android Pixel 8：✅ SUCCESS**，3 步 / 23.3s（09-05 的 0.80.0 构建同场景也是 3 步通过）。
  前一次尝试 maxStep=6 失败：第 1 步模型只回了 `perform_keypress BACK`（24 个 completion token，无描述、无 memo），属模型波动；随后 5 步感知与导航正确，第 6 步已点到 Battery 但步数耗尽。
- **iOS 12 mini / 13 Pro：❌ 未验证**（验证资源）。两台都已解锁，上游的 tunnel 唤醒（`xcrun devicectl device info details`）、iproxy 转发、runner 安装均走通，
  启动 runner 时报 "Developer App Certificate is not trusted"：需在手机 设置→通用→VPN 与设备管理 里信任开发者证书后重跑。
- 09-05 附带发现：Pixel 8 的 UIAutomator instrumentation 曾整机卡死（旧版二进制与手工 `am instrument` 同样卡住），`pm uninstall dev.mobile.maestro{,.test}` + `adb kill-server` 后恢复。
