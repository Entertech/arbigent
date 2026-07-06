# arbigent run 参考模板

复制本目录作为测试项目的起点。配好之后日常命令就是一行:

```bash
arbigent run                     # 跑全部场景(模型/设备/项目文件都从 settings 读)
arbigent run --tags=smoke        # 按标签筛选
arbigent run --scenario-ids=smoke-settings-battery \
  --variables 'search_term=Bluetooth'
```

## 目录结构

| 文件 | 作用 | 进 git? |
|---|---|---|
| `project.yaml` | 测试场景(goal / 步数 / 标签 / 变量) | ✅ |
| `.arbigent/settings.yml` | 团队共享运行默认值(模型、endpoint、os) | ✅ |
| `.arbigent/settings.local.yml` | 个人覆盖(device 等),从 `settings.local.yml.example` 复制 | ❌(加进 .gitignore) |

优先级:命令行参数 > `settings.local.yml` > `settings.yml`。

## 首次配置(每人一次)

```bash
# 1. 安装(需要 org 的 SSH key + gh auth login)
brew tap entertech/tap git@github.com:Entertech/homebrew-tap.git
brew install entertech/tap/arbigent

# 2. API key(找管理员要,写进 ~/.zshrc)
export OPENAI_API_KEY="<智谱 key>"        # 默认模型 glm-5v-turbo 用这个

# 3. 指定手机(多台设备时)
arbigent devices                           # 查设备 ID
cp settings.local.yml.example .arbigent/settings.local.yml
#   然后把里面的 device 改成你的设备 ID

# 4. 冒烟验证(手机解锁、保持亮屏)
arbigent run --scenario-ids=smoke-settings-battery
```

跑完看 `arbigent-result/report.html`。模型选型与常见坑见
[docs/quickstart-zh.md](../../docs/quickstart-zh.md) 和 [docs/ai-providers.md](../../docs/ai-providers.md)。
