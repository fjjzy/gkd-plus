# GKD-Plus 与上游 GKD 差异文档

本文档记录 GKD-Plus（`fjjzy/gkd-plus`）相对上游 [gkd-kit/gkd](https://github.com/gkd-kit/gkd) 的全部差异，用于：
- 同步上游代码时快速定位需要重新应用的本地改动；
- 向使用者说明本分支新增的能力。

> 当前基线：上游 `e732da05`（已合并 `Merge upstream gkd-kit/gkd main into main`）。
> 上游已将模块 `:app` 重命名为 `:gkd-app`，包名由 `li.songe.gkd` 改为 `li.gkd.app`，并拆分出 `:gkd-db` / `:gkd-selector` / `:gkd-hidden-api` 模块。本分支所有改动均已适配新架构。

---

## 一、新增功能

### 1. AI 自动规则生成

快照保存后自动调用大模型生成 GKD 订阅规则，并写入「订阅 - 本地规则」（自动来源直接入库；手动来源生成后由用户在工作台点「加入规则」确认）。

- 支持 OpenAI 与 Anthropic 两种协议
- 支持模型列表拉取、连接测试
- 多会话并发生成：不同快照各自独立处理、互不阻塞；同一会话重复触发会复用正在进行的任务

**新增文件**
- `gkd-app/src/main/kotlin/li/gkd/app/util/AiRuleGenerator.kt` — AI 调用、JSON 提取/解析、规则写入核心
- `gkd-app/src/main/kotlin/li/gkd/app/util/AiModelCatalogUtils.kt` — 协议/模型选项与展示名
- `gkd-app/src/main/kotlin/li/gkd/app/feature/settings/AiSettingsDialog.kt` — AI 配置对话框
- `gkd-app/src/main/assets/gkd-rule-generator-prompt.md` — 内置提示词

**改动文件**
- `gkd-app/src/main/kotlin/li/gkd/app/data/settings/SettingsStore.kt` — 新增 `AiConfig` 数据类及 `aiEnable` / `aiConfig` 字段
- `gkd-app/src/main/kotlin/li/gkd/app/snapshot/SnapshotCapture.kt` — 快照保存后按开关触发 AI 生成
- `gkd-app/src/main/kotlin/li/gkd/app/feature/settings/AdvancedPage.kt` — 新增「AI 自动规则」区块与配置入口
- `gkd-app/src/main/kotlin/li/gkd/app/feature/settings/AdvancedVm.kt` — 新增 `setAiEnable`

### 2. AI 规则工作台

按应用保存会话，可回看历史、继续追问；支持停止生成、清空会话，以及把当前对话导出为文件分享。

**新增文件**
- `gkd-app/src/main/kotlin/li/gkd/app/feature/ai/AiWorkbenchPage.kt`
- `gkd-app/src/main/kotlin/li/gkd/app/feature/ai/AiWorkbenchVm.kt`
- `gkd-app/src/main/kotlin/li/gkd/app/feature/ai/AiConversationPage.kt`
- `gkd-app/src/main/kotlin/li/gkd/app/feature/ai/AiConversationVm.kt`
- `gkd-app/src/main/kotlin/li/gkd/app/feature/ai/AiConversationStore.kt` — 会话持久化（消息 / 选中组件 / 状态）
- `gkd-app/src/main/kotlin/li/gkd/app/feature/ai/AiConversationExport.kt` — 对话导出与分享
- `gkd-app/src/main/kotlin/li/gkd/app/feature/ai/AiRuleLauncher.kt` — 会话创建与生成入口

### 3. 快照选择器（多选组件）

在快照上缩放选择范围、多选组件、查看组件属性；选中结果作为上下文交给模型，支持「对话生成」与「直接生成」。

**新增文件**
- `gkd-app/src/main/kotlin/li/gkd/app/feature/ai/AiSnapshotPickerPage.kt`
- `gkd-app/src/main/kotlin/li/gkd/app/feature/ai/AiSnapshotPickerVm.kt`
- `gkd-app/src/main/kotlin/li/gkd/app/feature/ai/SnapshotNodeSelector.kt`

### 4. 思考等级自适应

按模型名自动匹配合适的思考参数（Claude / GPT-5 / 智谱 GLM / 豆包 / 千问 / DeepSeek 等），也支持手写自定义 JSON；服务端不接受时降级为「不发送」并记住。

**新增文件**
- `gkd-app/src/main/kotlin/li/gkd/app/util/AiThinkingUtils.kt`

### 5. Max Tokens 自适应

预算偏小时自动抬底、输出被截断时翻倍、被服务端拒绝时回退并记住上限。

**新增文件**
- `gkd-app/src/main/kotlin/li/gkd/app/util/AiMaxTokensUtils.kt`

### 6. AI 配置备份/恢复

大模型配置（API URL、Key、模型等）随设置一起纳入备份/导入体系。

**改动文件**
- `gkd-app/src/main/kotlin/li/gkd/app/data/settings/SettingsStore.kt` — `aiConfig` 随设置进入 `AppStore` 备份条目

### 7. 首页「手动更新 APP」入口

在首页新增「手动更新 APP」卡片，弹窗提供夸克网盘 / 百度网盘下载地址，方便无加速器用户更新。

**改动文件**
- `gkd-app/src/main/kotlin/li/gkd/app/ui/home/DashboardPage.kt`

### 8. 更新检查与多源兜底

更新渠道指向本仓库的 GitHub Release；GitHub 不可达时自动回退到 Gitee 与 npm 镜像获取版本信息，并可经夸克网盘下载。

**改动文件**
- `gkd-app/src/main/kotlin/li/gkd/app/util/Option.kt` — `UpdateChannelOption` 指向 `api.github.com/repos/fjjzy/gkd-plus/releases/latest`
- `gkd-app/src/main/kotlin/li/gkd/app/util/Upgrade.kt` — `GitHubRelease` 解析、版本比较、`absoluteDownloadUrl`、多源并发兜底
- `gkd-app/src/main/kotlin/li/gkd/app/util/Constants.kt` — 兜底源地址（Gitee raw / npmmirror）
- `.github/workflows/Build-Release.yml` — CI 同步 Gitee `version.json`、发布 npmmirror npm 包

### 9. 导出日志的敏感信息脱敏

导出日志（含 store 配置、日志文件）时自动把 API Key / Bearer Token / x-api-key 替换为 `********`，避免密钥泄露。

**改动文件**
- `gkd-app/src/main/kotlin/li/gkd/app/util/FolderUtils.kt` — `buildLogFile()` 复制到临时目录并脱敏；新增 `redactSensitiveText`

### 10. 版本声明

「关于」页新增版本声明，说明本分支为社区改版，非官方版本。

**改动文件**
- `gkd-app/src/main/kotlin/li/gkd/app/feature/settings/AboutPage.kt`

### 11. 简易规则

在 `应用规则` 页右下角展开按钮中提供「简易规则」入口，填定位文字或控件 ID 即可生成一条规则，无需手写选择器。

- 文字 / ID 二选一，都填时优先按 ID；选择器分别为 `[text="..."]` 与 `[id="..."]`，值内的 `\` 与 `"` 会转义
- 可选「通过返回键关闭」：动作为 `back`，否则为 `click`
- 规则名取文字（文字为空时取 ID），追加到本地订阅（`LOCAL_SUBS_ID`）的当前应用下；同名规则自动加 `-2`、`-3` 后缀，不覆盖已有规则
- 与同菜单里的「完整规则」（跳转规则编辑页）并列

**新增文件**
- `gkd-app/src/main/kotlin/li/gkd/app/ui/SimpleRuleDialog.kt` — 简易规则弹窗（文字 / ID 输入、「通过返回键关闭」开关、输入示例）

**改动文件**
- `gkd-app/src/main/kotlin/li/gkd/app/ui/AppConfigPage.kt` — 右下角悬浮按钮展开菜单新增「简易规则 / 完整规则」入口
- `gkd-app/src/main/kotlin/li/gkd/app/ui/AppConfigVm.kt` — 新增 `addSimpleRule`，生成规则组并追加到本地订阅
- `gkd-app/src/main/res/values/strings.xml` — `rule_add_simple` / `rule_add_full` / `simple_rule_*` 文案

### 12. 无障碍开机自愈

重启后自动写回无障碍服务，避免 ColorOS 等系统移除第三方无障碍条目。

- `BootRestoreReceiver` 监听 `BOOT_COMPLETED` / `QUICKBOOT_POWERON`，在 `enableAutomator && useA11y` 时写回 `enabled_accessibility_services`，带重试与「移除 → 加回」修复
- `PrivilegeContext.ensureWriteSecureSettings()`：Root 走 `grantRuntimePermission`；Shizuku / ADB 绕过 priv-kit 的 `isPermissionRestricted()` 守卫，由特权服务执行一次 `pm grant`。权限持久化后，开机不再依赖特权服务在线
- 工作模式页文案说明「连接一次特权服务（Shizuku 即可）」

**新增文件**
- `gkd-app/src/main/kotlin/li/gkd/app/service/BootRestoreReceiver.kt`

**改动文件**
- `gkd-app/src/main/AndroidManifest.xml` — `RECEIVE_BOOT_COMPLETED` 权限与 receiver
- `gkd-app/src/main/kotlin/li/gkd/app/priv/PrivilegeContext.kt` — `ensureWriteSecureSettings`
- `gkd-app/src/main/res/values/strings.xml` — 工作模式说明文案

---

## 二、品牌与构建配置

| 项目 | 上游 | GKD-Plus |
|------|------|----------|
| `applicationId` | `li.songe.gkd` | `li.songe.gkd.plus` |
| `versionCode` / `versionName` | 92 / 1.12.1 | 93 / 1.12.1-plus.N |
| 根项目名 | `gkd` | `gkd-plus` |
| 应用名 | `GKD` | `GKD-Plus` |
| APK 文件名 | `gkd-v{ver}.apk` | `gkd-plus-v{ver}.apk` |
| 仓库地址 | `github.com/gkd-kit/gkd` | `github.com/fjjzy/gkd-plus` |

**改动文件**
- `gkd-app/build.gradle.kts`
- `settings.gradle.kts`
- `gkd-app/src/main/res/values/strings.xml`
- `gkd-app/src/main/kotlin/li/gkd/app/util/Constants.kt`（`REPOSITORY_URL`）
- `gkd-app/src/main/kotlin/li/gkd/app/App.kt`（`commitUrl`）

---

## 三、测试

- `gkd-app/src/test/kotlin/li/gkd/app/util/AiThinkingUtilsTest.kt` — 校验思考参数按模型名翻译
- `gkd-app/src/test/kotlin/li/gkd/app/util/AiMaxTokensUtilsTest.kt` — 校验 Max Tokens 抬底 / 截断翻倍 / 上限回退
- `gkd-app/src/test/kotlin/li/gkd/app/util/FolderExtTest.kt` — 校验日志脱敏规则
- `gkd-app/src/test/kotlin/li/gkd/app/util/BackupUtilsTest.kt` — 校验备份数据保留真实 apiKey（不被脱敏）

---

## 四、同步上游的方法

上游结构已重构，直接 `git merge` 会产生大量冲突。推荐流程：

1. `git fetch origin`（origin 指向上游 gkd-kit/gkd）
2. `git merge origin/main`，冲突文件一律先取上游版本，得到干净的上游架构
3. 按本文档「一、新增功能」逐项把本地改动重新应用到新架构上
4. 编译验证：`./gradlew :gkd-app:assembleGkdDebug`
5. 单元测试：`./gradlew :gkd-app:testGkdDebugUnitTest`

> 注意：上游重构后 `SnapshotExt` 拆分为 `SnapshotCapture` + `SnapshotRepository`，
> `subsMapFlow` 迁到 `SubscriptionState`，`LOCAL_SUBS_ID` 迁到 `:gkd-db` 模块，
> `updateSubscription()` 改为 `SubscriptionRepository.update(id, transform)`，
> `launchTry` 改为 `launchLogged`，`toast` 改为 `ToastUtils.toast`，
> 选择器 `Selector.parseOrNull` 改为 `Selector.compile` + `MatchOptions`。
