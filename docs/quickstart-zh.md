# arbigent 快速上手(测试同学版)

arbigent 是 AI 驱动的移动端 UI 测试工具:你用自然语言写"测试目标",AI 看着屏幕截图
一步步操作真机,直到目标达成或失败。不需要写查找控件的脚本。

## 一次性安装

前提(工程师机器一般都有):

- 有权限访问 Entertech org 的 **SSH key**
- 登录过的 **GitHub CLI**:`brew install gh && gh auth login`(下载私有 release 用)
- Android 测试:`brew install android-platform-tools`(即 adb)
- iOS 测试:装好 Xcode

安装:

```bash
brew tap entertech/tap git@github.com:Entertech/homebrew-tap.git
brew install entertech/tap/arbigent
arbigent --help   # 验证
```

## 配置 AI 模型

推荐 **glm-5v-turbo**(智谱,国内直连,实测在多步导航任务上最稳最快)。
向管理员要 API key,放进环境变量:

```bash
export OPENAI_API_KEY="<智谱 key>"   # 建议写进 ~/.zshrc
```

## 写测试场景

**最快起步:直接复制仓库里的参考模板 [examples/run-template](../examples/run-template/)**——
含带注释的 `project.yaml`、团队共享的 `.arbigent/settings.yml`(模型/endpoint 默认值,
配好后日常只需 `arbigent run`)和个人覆盖示例(设备 ID)。以下为手写说明。

新建一个目录,里面放 `project.yaml`:

```yaml
scenarios:
- id: "settings-battery"
  goal: "Open the 'Battery' page inside the Settings app. Tap the Battery entry in the settings list (scroll down if it is not visible). The goal is reached when the Battery screen is shown."
  maxStepCount: 8
  initializationMethods:
  - type: "LaunchApp"
    packageName: "com.android.settings"

- id: "settings-search"
  goal: "In the Settings app, use the search function to search for '{{search_term}}' and open the first search result."
  maxStepCount: 10
  initializationMethods:
  - type: "LaunchApp"
    packageName: "com.android.settings"
```

写 goal 的技巧:

- **写清完成判定**:加一句 "The goal is reached when ..."(减少 AI 提前宣布成功/多绕路)。
- **`maxStepCount` 给足但别过大**:一步 ≈ 一次点击/滑动;到上限即判失败。
- **`{{变量}}`** 在命令行用 `--variables` 传入,同一场景可以换数据复用。
- goal 用英文写(模型对英文指令的执行最稳定)。

## 运行

手机 USB 连接、**已解锁并保持亮屏**(锁屏 AI 过不去,会空转到失败),然后:

```bash
cd <project.yaml 所在目录>

arbigent run \
  --os=android \
  --project-file=project.yaml \
  --ai-type=openai \
  --openai-endpoint="https://open.bigmodel.cn/api/paas/v4/" \
  --openai-model-name="glm-5v-turbo" \
  --scenario-ids="settings-battery" \
  --variables 'search_term=Bluetooth'
```

- 去掉 `--scenario-ids` 则跑全部场景;可用 `--tags` 按标签筛选。
- iOS 换 `--os=ios`(需要 Xcode,首次会往手机装 XCTest runner)。
- 连了多台设备时,先 `arbigent devices` 查设备 ID,再加 `--device=<DEVICE ID>` 指定目标机。
- 先验证配置不花钱:加 `--dry-run`。

## 看结果

跑完后当前目录出现 `arbigent-result/`:

- **`report.html`** — 打开就是完整报告:每一步的截图(带操作标注)、AI 的思考和动作。
- `summary.txt` — 一眼看成功/失败和失败原因。
- `screenshots/`、`jsonls/` — 原始截图和 API 日志,报 bug 时把整个目录打包。

## 常见坑

| 现象 | 原因 / 处理 |
|---|---|
| 每步都说 "screen is identical",空转到失败 | 手机锁屏或灭屏了。解锁、亮屏,开发者选项里开"充电时屏幕不休眠" |
| 连了多台设备,跑到了别的手机上 | 先 `arbigent devices` 看设备列表,然后 `arbigent run --device=<DEVICE ID> ...` 指定目标机(Android 用序列号,iOS 用 UDID) |
| `tool_choice ... not support ... thinking mode` 报错 | 该模型默认开思考模式(如 DashScope qwen3.6-flash)。在 project.yaml 里关掉即可:`settings:` → `aiOptions:` → `extraBody:` → `enable_thinking: false`(注意必须嵌在 `settings:` 下) |
| Gemini 报 `400 Unknown name "enable_thinking"` | 上一行那个参数是 qwen 专用的;Gemini 对未知字段严格校验会直接拒收。切 Gemini 前把 project.yaml 里的 `extraBody` 段删掉或注释 |
| Gemini 每步都报 `Failed to call API: 400/404` | 忘了传 `--gemini-model-name`(默认值 gemini-1.5-flash 已下线),用 `gemini-3-flash-preview` |
| `env: gh: No such file or directory`(安装时) | 没装 GitHub CLI:`brew install gh && gh auth login` |
| iOS 连不上 / XCTest 超时 | 按序排查:① **iPhone 屏幕锁了**——runner 日志(arbigent-result/maestro-xctest-logs/)出现 "failed to initialize for UI testing" 基本就是它,把 设置→显示与亮度→自动锁定 设为"永不";② 多台 iPhone(含无线配对)时 CLI 会选错设备,用 `--device=<UDID>` 钉死;③ 杀残留进程 `pkill -f xcodebuildmcp; pkill -f "iproxy --udid"`;④ 都不行再重启 iPhone |
| 场景步数/重试配置不生效 | 字段名是 `maxStep` 和 `maxRetry`(写错如 maxStepCount 会被静默忽略,默认 10 步 / 重试 3 次) |

模型选型、各家 API 的差异见 [ai-providers.md](ai-providers.md)。
