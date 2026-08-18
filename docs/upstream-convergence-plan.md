# Upstream 收敛计划（hybrid-grounding → takahirom/arbigent main）

基线：merge-base 为 `34310d2`（#385 Journeys XML import）。upstream/main 领先 119 commits（含 PR #386–#408），本 fork 领先 71 commits。
关键事实：双方各自独立实现了 iOS 真机（XCTest over devicectl + iproxy）支持，且上游把 Maestro 依赖从 Maven 坐标切换为下载官方 maestro.zip（pin 2.7.0 + sha256，`gradle/maestro.gradle.kts`），而我们发布 `ai.looktech:maestro-*`（2.6.1-looktech.2）到 Maven Central。

> 冲突数勘误：当前 `git merge-tree --write-tree --name-only HEAD upstream/main` 实测为 **10 个冲突文件**（不是早前记录的 37；6823eb8 已提前合入 5 个上游提交，缩小了冲突面）。列表见第 3 节。

## 1. 两套 iOS 真机实现逐文件对照

文件映射（我方 HEAD ↔ upstream/main，均已完整阅读）：

| 我方文件 | 上游对应文件 | 关系 |
|---|---|---|
| `IosRealXCTestDevice.kt`（Config + IosRealDeviceCatalog + devicectl wrapper） | `IosRealDeviceConfiguration.kt` + `DeviceFinder.kt`（发现逻辑）+ `ArbigentDevicectlIOSDevice.kt` | 同职责，结构完全不同 |
| `IosRealXCTestDriverProducts.kt`（54 行，借 maestro-cli DriverBuilder） | `IosRealDriverProducts.kt`（自建 xcodebuild + 缓存体系） | 同职责，上游远更完备 |
| `IosRealXCTestInstaller.kt`（外部 xctestrun 安装器） | 无 | 我方独有 |
| `IosRealXCTestPortForwarder`（内嵌于 ArbigentDeviceOs.kt，约 55 行） | `IosRealXCTestPortForwarder.kt`（独立文件，含孤儿回收） | 同名同职责，上游更完备 |
| `IosCodeSigningTeamResolver.kt` | `IosCodeSigningTeamResolver.kt` | add/add 冲突，实现不同 |
| `IosRealMirrorDevice.kt`（含 ArbigentDevicectlClient，镜像/纯视觉后端） | 无 | 我方独有 |
| `DeviceFinder.kt` / `ArbigentDeviceOs.kt`（IOSRealXCTest 类） | `DeviceFinder.kt` / `ArbigentDeviceOs.kt`（IosReal 类） | 内容冲突 |
| CLI：`--device` 统一选项 + `DevicesCommand.kt` | CLI：`--ios-xctest-apple-team-id` / `--ios-real-device-id` / `--ios-real-device-port`（RunCommand + RunTaskCommand） | 选项体系不同 |
| 测试 `IosRealXCTestDeviceTest.kt`（9 例） | `IosRealDeviceTest.kt`（32 例，覆盖 team OU 解析 / 端口 / 孤儿回收决策 / UDID 掩码 / mobileprovision / devicectl launch / zip-slip） | 上游覆盖更全 |

能力对照与保留决策：

| 能力 | 我方 | 上游 | 决策建议 | 理由 |
|---|---|---|---|---|
| devicectl 设备目录 | `devicectl list devices` JSON，按 `pairingState=paired` 过滤；`canConnect` 取自 capabilities 的 connectdevice feature；stderr 落文件防管道阻塞 | maestro `LocalIOSDevice().listDeviceViaDeviceCtl()`，按 `tunnelState=connected` 过滤，且会先用 `devicectl device info details` **唤醒 CoreDevice tunnel**（读操作）再重新列举 | keep-upstream | tunnel 唤醒解决了 paired-but-disconnected 的发现抖动（我们靠 canConnect 警告 + 强连尝试绕过），机制更稳；我方 includeUnconnectable 列表语义再移植 |
| canConnect 抖动处理 | 显式选中的设备即使 not-connectable 也警告后继续连（connectToDevice 为准） | 靠 wake 后仍 disconnected 则不显示 | merge-both | 上游 wake 后我方“显式指定则不硬失败”的语义仍有价值，移植到 filter 分支 |
| 设备 pin（CLI/env） | 统一 `--device`（Android serial / iOS UDID）；env `ARBIGENT_IOS_REAL_DEVICE_ID`、settings `ios-real-device-id`、兼容 `MAESTRO_IOS_MIRROR_DEVICE_ID` | `--ios-real-device-id` + 同名 env；config 通过 `ArbigentIosRealDeviceConfiguration` DI 逐层传递（无全局态） | merge-both | 保上游 DI（#402 专门去全局化），把 `--device` 作为糖衣映射回 iosRealDeviceId / simulator UDID 再移植 |
| 端口配置 | env `ARBIGENT_IOS_XCTEST_PORT`、host 可配，默认 22087 | `--ios-real-device-port` + env `ARBIGENT_IOS_REAL_DEVICE_PORT`，1..65535 严格校验、host 固定 127.0.0.1 | keep-upstream | 校验失败即报错优于静默回退；host 可配无实际场景（runner 只绑 loopback） |
| Team-id 自动检测 | `security find-identity` 后正则抓 CN 括号 `(XXXXXXXXXX)`；单团队缓存、多团队警告置空 | `security find-certificate -c "Apple Development"` + openssl 取证书 **OU**；明确注释 CN 括号是个人 id 而非 team id；0/多团队直接报可操作错误；日志掩码 `maskTeamId` | keep-upstream | 我方实现对个人开发者证书会抓错 id（CN 括号 ≠ team），上游 0d32f48 专门修的就是这个坑；缓存收益小（上游只在 connect 时解析一次） |
| DEVELOPMENT_TEAM env 回退 | 有 | 无 | keep-ours（小移植） | CI 里常见变量，一行回退成本低 |
| Driver 产物解析/构建 | 显式 products dir env → `~/.maestro` 预构建复用 → classpath 资源 → maestro-cli `DriverBuilder`（依赖 fork 补齐的 driver/ios 源码） | `IosRealDriverProducts`：从打进 arbigent-core 资源的完整 runner Xcode 工程自建；缓存 `~/.arbigent/ios-real-driver/<ver>/<teamHash>/<deviceHash>`，marker 校验 schema/maestro 版本/team/device/Xcode 版本/源码校验和/**mobileprovision 过期与设备覆盖**；跨进程文件锁 + 原子换入 + provisioning 竞态重试 + team-id 脱敏日志 | keep-upstream | 上游解决了免费 7 天 profile 过期、并发构建、脏缓存等我们没处理的问题；我方显式 products-dir override 若 CI 仍需要再作为增量选项移植 |
| 外部 xctestrun（预构建 runner） | `ArbigentExternalXCTestInstaller`：xcodebuild test-without-building + HTTP /status 轮询 + devicectl 卸载 | 无 | keep-ours（re-port） | CI 分发预构建 runner 的唯一路径；上游总是现场构建 |
| reinstallDriver | settings 可配，默认 false | 硬编码 true | keep-upstream 为默认，可后续加开关 | 每次重装更稳；我们当年设 false 是为省真机安装时间，可再评估 |
| iproxy 生命周期 | 启动 + 500ms 存活检查 + 日志文件 + close 时销毁；iproxy 缺失仅警告 | 启动检查 + shutdown hook + **ownership pidfile（记录 iproxy/owner pid+starttime 防 PID 复用）+ 孤儿回收 + 端口被占诊断** + `brew install libimobiledevice` 提示 | keep-upstream | 崩溃残留 iproxy 占端口是我们实际踩过的痛点，上游系统性解决且有 32 例测试兜底 |
| 多台 iPhone 未指定 id | 按 canConnect/name 排序取第一台 | 拒绝猜测，报错并列出掩码 UDID 候选（`maskedUdidLabels`） | keep-upstream | devicectl 顺序不稳定，静默选错台危害大 |
| Android 设备选择 | `--device`/`ANDROID_SERIAL`/`ARBIGENT_ANDROID_DEVICE_ID`（serial 或 adb transport id 匹配，未选中的 dadb 句柄关闭） | **无**（已核实：`Dadb.list().map{...}` 直取，connectDevice 无 deviceId 参数） | keep-ours（re-port） | 上游完全没有该能力 |
| `arbigent devices` 列表命令 | 有（含 not-connectable 提示、忽略 env pin、表格输出） | **无**（已核实 CLI 目录无 DevicesCommand；UI 用 `includeAllIosDevices=true` 列全量） | keep-ours（re-port） | 移植时改基于上游 `fetchAvailableDevicesByOs(includeAllIosDevices=true)`；注意上游把 UDID 视为敏感信息（只展示前 8 位掩码），devices 命令需要打印完整 id 供 `--device` 使用，需与上游确认或本地保留全量输出 |
| app 生命周期（devicectl） | launch/uninstall 实现；`clearAppState` 用 devicectl copy 空目录清 data container；`openUrl` 支持 bundleId+payload-url 深链；setPermissions 显式 no-op | launch（`--` 终止选项解析 + launchArguments 转换）、install（zip-slip 防护）；clearAppState/openLink **显式抛 Unsupported**（宁可失败不假装成功） | merge-both | 上游 launch/install 更严谨；我方 clearAppState/openUrl 是真实现，可在上游骨架上恢复（也是好的上游贡献候选） |
| 镜像后端（vision-only） | `IosRealMirrorDevice`（MCP command + devicectl，配合 hybrid-grounding 纯截图落点） | 无 | keep-ours（re-port） | 我方核心差异化能力，与 ArbigentDevice.kt 的 tree-optional 改动配套 |
| 设备预热 warm-up | IOS simulator 类内私有实现 | 提炼为 `warmUpIosDevice()`，真机/模拟器共用，且修复 InterruptedException 吞掉问题 | keep-upstream | 上游是我们同一逻辑的泛化+修复 |
| 错误信息质量 | 基本可操作 | 系统性更好（brew 提示、端口占用归因、构建日志落盘+脱敏、掩码 UDID 候选列表） | keep-upstream | — |
| 测试 | 9 例（catalog 解析/选择、smoke） | 32 例纯单元（FakeExecutor 注入） | keep-upstream + 保留我方 selectDevices 语义用例改写 | — |

## 2. Maestro 供应链决策

我方 3 个 Maestro 补丁在 fork（/Volumes/CSVolume/Documents/entertech/Maestro-main，本地 main 已合并 upstream post-2.8.0，fork 独有 14 commits）中的现状，与 maestro upstream 2.8 的核实结论：

1. **iOS backPress = 左缘滑动**（95322682，IOSDriver.kt）：upstream 2.8 的 `backPress()` 仍是空实现 `{}` —— 补丁仍必需。
2. **screen-settle 超时可配**（同 95322682，`maestro.ios.screenSettleTimeoutMs` 系统属性 / `MAESTRO_IOS_SCREEN_SETTLE_TIMEOUT_MS`）：upstream 2.8 仍硬编码 `SCREEN_SETTLE_TIMEOUT_MS` —— 补丁仍必需（arbigent 侧 `ios-settle-timeout-ms` 设置依赖它）。
3. **ScreenSizeHelper.swift 朝向回退**（ceb7e5d7，faceUp/faceDown/unknown 回退 portrait、补 portraitUpsideDown 坐标换算、去掉 fatalError）：upstream 2.8 该文件仍为旧逻辑（unknown-only + `default: fatalError`）—— 补丁仍必需。三个补丁均已随本地 `68c1cda6` 干净落在 upstream 2.8 之后的代码上。

三个选项：

- **(a) 维持 ai.looktech Maven Central 线**：改造上游 maestro.gradle.kts 消费 Maven 坐标（或保留 libs.versions.toml）。工作量小在 arbigent 侧，但要害在：上游 `IosRealDriverProducts` 依赖 gradle 阶段从 **maestro 源码 tar 包**抽出的完整 runner Xcode 工程（打进 arbigent-core 资源 `ios-real-driver/runner` + `BuildConfig.MAESTRO_VERSION`），Maven jar 不携带它（上游明确注明 maestro-cli jar 里的 driver/ios 副本缺 MaestroDriverLib，不可用）。走 (a) 等于同时放弃上游整套 iOS 真机实现或自行重造资源打包，且长期背 Maven Central 签名/发布运维。
- **(b) 补丁上交 mobile-dev-inc，改用官方 zip**：orientation 回退和 settle-timeout env 是干净的 bug fix/低风险增强，上游接受概率高；backPress-as-swipe 是语义决策（上游有意留空），可能被拒或要求做成 opt-in。节奏不可控（review 周期数周起），期间收敛被阻塞。
- **(c) 自建 maestro.zip release，把 zip 机制指向 Entertech/Maestro**：fork 已继承上游 `publish-cli.yaml`（构建 maestro.zip）；从 upstream `cli-2.7.0` tag 切分支 cherry-pick 3 个补丁（改动面小，确认可干净应用）打 `cli-2.7.0-looktech.1`，GitHub Release 挂 zip；arbigent 侧只改 maestro.gradle.kts 的 version / 两个 URL / 两个 sha256（zip + 源码 tar 均来自 Entertech/Maestro）。上游 arbigent 代码按 2.7.0 API 编写，先对齐 2.7 再单独提 2.8 升级，风险隔离。CI：`publish-release.yaml`（Maven Central 聚合发布）可退役，仅为旧分支保留只读；不再维护签名密钥轮换。

**推荐：(c) 为主，(b) 并行推进。** 理由：(c) 让 arbigent 与上游构建机制零分叉（未来上游 bump 版本时我们只是改 pin），完整继承上游 iOS 真机实现赖以工作的 runner 源码打包管线，保住 3 个仍必需的补丁，同时甩掉 Maven Central 运维。并行把补丁 3（orientation）和补丁 2（settle env）提交 mobile-dev-inc，被合并后 fork 差异收敛到只剩 backPress 一个语义补丁。

## 3. 合并顺序建议

当前 10 个冲突文件（`git merge-tree --write-tree --name-only HEAD upstream/main` 实测）按域分组：

- **CLI（4）**：`arbigent-cli/src/main/kotlin/CommonOptions.kt`、`RunCommand.kt`、`RunTaskCommand.kt`、`main.kt` —— 我方 `--device`/DevicesCommand/Codex provider vs 上游 `--ios-*` 三选项 + Graph/Instruction/Guide 子命令 + dispatcher 注入。
- **core 设备层（4）**：`ArbigentDevice.kt`（我方 hybrid-grounding tree-optional + 层级缓存 vs 上游 Connection 原子换入/幂等 teardown）、`ArbigentDeviceOs.kt`、`DeviceFinder.kt`、`IosCodeSigningTeamResolver.kt`（add/add）。
- **构建（2）**：`arbigent-core/build.gradle.kts`（Maven 坐标 vs maestroJars + runner 资源打包）、`gradle/libs.versions.toml`（我方 maestro 条目 vs 上游删除）。

分阶段：

1. **Phase 0 — 预备**：提交/收纳工作区未提交改动（ArbigentAgent.kt、ArbigentProjectSerializer.kt、Fakes.kt）；打 tag 备份。先在 Entertech/Maestro 完成第 2 节 (c)：出 `cli-2.7.0-looktech.1` zip + sha256（构建冲突的解法依赖它）。
2. **Phase 1 — 可选低风险预合**：先 `git merge 0693ad7`（#397 之前：#386 reusable-scenarios、#388–#396 UI/graph/guide/instruction、#389 toolchain），这段与 iOS 真机无关、基本自动合并，缩小主合并 diff、便于 bisect。
3. **Phase 2 — 主合并 `git merge upstream/main`**，逐文件策略：
   - **整取上游**：`IosCodeSigningTeamResolver.kt`（add/add 取上游）、`DeviceFinder.kt`、`ArbigentDeviceOs.kt`（以上游为基，先不带我方扩展）、两处 gradle 文件（上游机制 + 把 pin 改为 Entertech zip）；#403 dispatcher、#404 CodeQL、#405/#408 resolver 相关全部照收。
   - **手动删除我方被取代文件**（不会冲突、merge 会静默保留，须显式删）：`IosRealXCTestDevice.kt`、`IosRealXCTestDriverProducts.kt`；保留 `IosRealXCTestInstaller.kt`、`IosRealMirrorDevice.kt`（含 ArbigentDevicectlClient）、`DevicesCommand.kt`，暂时断开接线允许注释/最小 stub。
   - **merge-both**：`ArbigentDevice.kt`（上游 Connection/onClose 骨架 + 我方 tree-optional 空元素返回、hierarchy 缓存、foregroundPackage）；CLI 四文件（上游选项与子命令 + 我方 Codex config 分支与 DevicesCommand 注册）。
4. **Phase 3 — 我方特性在上游基座上重移植**（每项独立小提交）：① Android `--device`（上游无，已核实）；② `arbigent devices` 命令（基于 `includeAllIosDevices=true` + wake，处理 UDID 掩码策略）；③ iOS 统一 `--device` 糖衣 → `iosRealDeviceId`；④ DEVELOPMENT_TEAM env 回退；⑤ 外部 xctestrun 安装器与 products-dir override；⑥ 镜像后端 + hybrid-grounding 接线恢复；⑦ settle-timeout 设置→系统属性（fork maestro 在 call-time 读取，机制不变）；⑧（可选）clearAppState/openUrl 的 devicectl 实现回填并考虑上交 arbigent 上游。**extraBody 无需移植**：已核实上游具备完整 extraBody 管线（OpenAIAi/序列化器/测试），且相关文件不在冲突列表。
5. **Phase 4 — 验证清单**：`./gradlew test`（重点 IosRealDeviceTest 32 例 + CliTest + 我方保留用例）；`./gradlew installDist` + `arbigent --help`/`arbigent devices`；Android 真机 smoke（`run --os=android --device=<serial>`）；iOS 真机 smoke（`run --scenario-ids="open-model-page"`，验证首次 runner 构建、二次缓存命中、kill -9 后重跑的 iproxy 孤儿回收、lockscreen 超时文档场景）；iOS 模拟器 smoke；UI 设备下拉列出 iPhone+模拟器；Entertech brew tap 发布链（build-cli.yaml + COMMITER_TOKEN formula bump）走一遍 dry-run。

## 4. Rejected alternatives

- **整体 keep-ours（不接上游 iOS 真机实现）**：放弃 OU 取 team-id 的正确性修复、provisioning 过期校验、iproxy 孤儿回收与 32 例测试；且 DeviceFinder/ArbigentDeviceOs 永久冲突，后续每次上游合并都重付成本。
- **对 119 个提交做 cherry-pick 挑选式合并**：丢失合并历史导致未来 `git merge upstream/main` 反复冲突同一批 hunks；merge-tree 实测只有 10 个冲突文件，整体 merge 成本可控。
- **选项 (a) 双轨 Maven 供应链**：需自行重造上游 runner 源码资源管线才能用上游 iOS 实现，等于为保发布渠道放弃收敛目标；签名/Central 运维长期背负。
