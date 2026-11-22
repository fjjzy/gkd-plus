# GKD-Plus 与上游 GKD 差异文档

本文档记录 GKD-Plus（`fjjzy/gkd-plus`）相对上游 [gkd-kit/gkd](https://github.com/gkd-kit/gkd) 的全部差异，用于：
- 同步上游代码时快速定位需要重新应用的本地改动；
- 向使用者说明本分支新增的能力。

> 合并上游后除本文档外，还必须逐项执行 [迁移验收清单](docs/plus-migration-checklist.md)：入口可达、开关有消费者、弹窗会渲染，不能只以编译通过为准。

> 当前基线：上游 `v1.13.0`（commit `0ee5d146`，2026-11-06）。
> 结构：`:gkd-app` 为 KMP library（`androidMain` / `commonMain`），`:gkd-android` 为 Android App 宿主；plus 的 AI 代码位于 `commonMain`，品牌与签名位于 `gkd-android`。
> 上游已将模块 `:app` 重命名为 `:gkd-app`，包名由 `li.songe.gkd` 改为 `li.gkd.app`，并拆分出 `:gkd-db` / `:gkd-selector` / `:gkd-hidden-api` 模块。本分支所有改动均已适配新架构。

---

## 一、新增功能

### 1. AI 自动规则生成

快照保存后自动调用大模型生成 GKD 订阅规则，并写入「订阅 - 本地规则」（自动来源直接入库；手动来源生成后由用户在工作台点「加入规则」确认）。

- 支持 OpenAI 与 Anthropic 两种协议
- 支持模型列表拉取、连接测试
- 多会话并发生成：不同快照各自独立处理、互不阻塞；同一会话重复触发会复用正在进行的任务

**新增文件**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/util/AiRuleGenerator.kt` — AI 调用、JSON 提取/解析、规则写入核心
- `gkd-app/src/commonMain/kotlin/li/gkd/app/util/AiModelCatalogUtils.kt` — 协议/模型选项与展示名
- `gkd-app/src/commonMain/kotlin/li/gkd/app/feature/settings/AiSettingsDialog.kt` — AI 配置对话框
- `gkd-app/src/commonMain/composeResources/files/gkd-rule-generator-prompt.md` — 内置提示词；2026-11-17 在「快照 JSON 格式」末尾加了「快照为空」的应对约定：可能是厂商系统/应用屏蔽无障碍（如小米对部分应用禁用无障碍、`FLAG_SECURE`、游戏全屏渲染）或截图时机不对，先让用户重截，仍为空则判定被屏蔽、不要凭空生成规则

**改动文件**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/settings/SettingsStore.kt` — 新增 `AiConfig` 数据类及 `aiEnable` / `aiConfig` 字段
- `gkd-app/src/commonMain/kotlin/li/gkd/app/snapshot/SnapshotCapture.kt` — 快照保存后按开关触发 AI 生成
- `gkd-app/src/commonMain/kotlin/li/gkd/app/ui/snapshot/SnapshotSettingsPage.kt` — 「AI 自动规则」区块与「自动截图后生成规则」开关，写入 `SettingsRepository.updateSettings { it.copy(aiEnable = ...) }`
- `gkd-app/src/commonMain/kotlin/li/gkd/app/ui/settings/AdvancedPage.kt` — 只保留「AI 规则设置」入口；`AdvancedViewModel` 不再有 `setAiEnable`

### 2. AI 规则工作台

按应用保存会话，可回看历史、继续追问；支持停止生成、清空会话，以及把当前对话导出为文件分享。

**新增文件**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/feature/ai/AiWorkbenchPage.kt`
- `gkd-app/src/commonMain/kotlin/li/gkd/app/feature/ai/AiWorkbenchVm.kt`
- `gkd-app/src/commonMain/kotlin/li/gkd/app/feature/ai/AiConversationPage.kt`
- `gkd-app/src/commonMain/kotlin/li/gkd/app/feature/ai/AiConversationVm.kt`
- `gkd-app/src/commonMain/kotlin/li/gkd/app/feature/ai/AiConversationStore.kt` — 会话持久化（消息 / 选中组件 / 状态）
- `gkd-app/src/commonMain/kotlin/li/gkd/app/feature/ai/AiConversationExport.kt` — 对话导出与分享
- `gkd-app/src/commonMain/kotlin/li/gkd/app/feature/ai/AiRuleLauncher.kt` — 会话创建与生成入口

### 3. 快照选择器（多选组件）

在快照上缩放选择范围、多选组件、查看组件属性；选中结果作为上下文交给模型，支持「对话生成」与「直接生成」。

**新增文件**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/feature/ai/AiSnapshotPickerPage.kt`
- `gkd-app/src/commonMain/kotlin/li/gkd/app/feature/ai/AiSnapshotPickerVm.kt`
- `gkd-app/src/commonMain/kotlin/li/gkd/app/feature/ai/SnapshotNodeSelector.kt`

### 4. 思考等级自适应

按模型名自动匹配合适的思考参数（Claude / GPT-5 / 智谱 GLM / 豆包 / 千问 / DeepSeek 等），也支持手写自定义 JSON；服务端不接受时降级为「不发送」并记住。

**新增文件**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/util/AiThinkingUtils.kt`

### 5. Max Tokens 自适应

预算偏小时自动抬底、输出被截断时翻倍、被服务端拒绝时回退并记住上限。

**新增文件**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/util/AiMaxTokensUtils.kt`

### 6. AI 配置备份/恢复

大模型配置（API URL、Key、模型等）随设置一起纳入备份/导入体系。

**改动文件**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/data/settings/SettingsStore.kt` — `aiConfig` 随设置进入 `AppStore` 备份条目

### 7. 首页「手动更新 APP」入口（已移除）

曾于首页加过「手动更新 APP」卡片，弹窗提供夸克网盘 / 百度网盘下载地址。因「设置 → 关于 → 检查更新」已覆盖 GitHub 不可达时的 Gitee / npm 兜底与夸克网盘下载降级，该卡片冗余，已删除（2026-11-09）；首页只保留「AI 规则工作台」「GKD 保活引导」两个 fork 入口。

**改动文件**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/ui/home/DashboardPage.kt` — 删除卡片与弹窗、`Autorenew` 图标引用

### 8. 更新检查、稳定版 / 测试版与多源兜底

更新渠道指向本仓库的 GitHub Release，并区分稳定版与测试版；GitHub 不可达时自动回退到 Gitee 与 npm 镜像获取版本信息，并可经夸克网盘下载。

- 稳定版读 `releases/latest`（GitHub 自动排除 prerelease），测试版读 `releases` 列表并按版本名挑最新，因此测试版能看到标记为 prerelease 的 Release
- 版本名比较支持 `-beta.N`：同基线 `x.y.z-beta.N` < `x.y.z` < `x.y.z-plus.N`；稳定版用户不会收到 beta 的升级提示
- 「忽略此版本」按 versionName 记录（GitHub 源的合成 versionCode 会把同基线 beta 与正式版混在一起）
- 兜底源（Gitee / npm）只由稳定版发版更新；beta tag 跳过，避免把走兜底的稳定版用户推到测试版
- CI 校验 tag 与构建出的 versionName 一致（`v{versionName}`），不一致直接失败
- 特权安装按下载 APK 的 versionName 校验，不再拿 GitHub 合成 versionCode 比对

**改动文件**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/network/AppLinks.kt` — `StableUpdate` / `BetaUpdate` 两个渠道地址
- `gkd-app/src/commonMain/kotlin/li/gkd/app/network/UpdateClient.kt` — `GitHubRelease` 解析（单对象与列表）、多源并发兜底
- `gkd-app/src/commonMain/kotlin/li/gkd/app/network/NewVersion.kt` — `compareVersionNames`（含 beta 预发布排序）、`absoluteDownloadUrl`
- `gkd-app/src/commonMain/kotlin/li/gkd/app/ui/update/UpdateStatus.kt` — 版本比较、忽略列表、下载降级
- `gkd-app/src/androidMain/kotlin/li/gkd/app/platform/PackageInstall.android.kt` — 特权安装按 versionName 校验下载包
- `gkd-app/src/commonMain/kotlin/li/gkd/app/ui/settings/AboutPage.kt` — 更新渠道切换与 beta 提示
- `.github/workflows/release.yml` — tag/versionName 校验、prerelease 标记、稳定版专属的 Gitee/npm 兜底发布

### 9. 导出日志的敏感信息脱敏

导出日志（含 store 配置、日志文件）时自动把 API Key / Bearer Token / x-api-key 替换为 `********`，避免密钥泄露。

**改动文件**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/storage/LogArchive.kt` — `build()` 把 store / db / subscription / crash / startup-log / 附加目录逐个复制到导出目录；新增 `redactSensitiveFiles()` 就地脱敏副本
- `gkd-app/src/commonMain/kotlin/li/gkd/app/util/Redaction.kt` — 新增 `redactSensitiveText`

### 10. 版本声明

「关于」页新增版本声明，说明本分支为社区改版，非官方版本。

**改动文件**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/feature/settings/AboutPage.kt`

### 11. 简易规则（李跳跳风格）

在 `应用规则` 页右下角展开按钮中提供「简易规则」入口，填「定位弹窗」和「关闭方式」即可生成一条规则，无需手写选择器；首页另有「如何使用李跳跳简易规则」引导卡片（底部「应用」页 → 选择应用 → 右下角加号 → 「添加简易规则」）。

- 两个输入框都支持：文字、完整 ID（含 `:id/`）、短 ID（`vid:xxx`，或直接写 `tv_close` 这类标识符）、bounds（`left,top,right,bottom` 或 `bounds:` 前缀），值内的 `\` 与 `"` 会转义
- 文字写法对齐李跳跳文档（李跳跳闭源，依据是社区整理的规则格式说明）：默认包含匹配（`text*=`），`+` 开头、`-` 结尾、`=` 完全相同；同时比对 text 与 desc；关闭方式写 `GLOBAL_ACTION_BACK` 等同返回键
- `&` 的含义与李跳跳不同：定位弹窗里的 `&` 拆成多条 `matches`，表示屏幕上同时出现这几个条件，可以分属不同控件（如 `移动&取消&确定`）；关闭方式里的 `&` 仍要求同一个控件全部满足
- 只填关闭方式：直接匹配并点击它；只填定位：匹配弹窗，动作为返回键
- 两个都填：`matches` 依次是定位弹窗的每个条件，最后一条是关闭控件；每条都要在屏幕上找到，点击最后一条命中的控件
- 「示例（8）」按钮循环切换：包含 / `+` 开头与 `=` 精确 / `-` 结尾 / `&` 多条件 / `GLOBAL_ACTION_BACK` / 完整 ID / 短 ID / bounds 各一组，每点一次把下一组写入两个输入框
- 弹窗里只放两个输入框；第一个输入框旁的信息按钮打开「填写说明」：文字写法（含 `&` 在两个输入框里含义不同的说明）、ID 写法、返回键写法、如何获取 bounds、如何快照（复用磁贴教程内容）
- 规则名取定位输入（为空时取关闭输入），追加到本地订阅（`LOCAL_SUBS_ID`）的当前应用下；同名规则自动加 `-2`、`-3` 后缀，不覆盖已有规则
- 简易规则和 AI 加入规则写进本地订阅后会顺手打开本地订阅（`requestEnabled(LOCAL, true)`），否则默认关闭的本地订阅会让新规则不生效
- 与同菜单里的「完整规则」（跳转规则编辑页）并列
- 「应用」页进入某个应用后，长按规则的批量菜单增加「删除」：只删选中项里本地订阅的应用规则，没选中本地规则时不可用（`AppConfigViewModel.deleteSelectedLocalGroups`）

**新增文件**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/ui/SimpleRuleDialog.kt` — 简易规则弹窗（定位 / 关闭输入、示例回填、bounds 帮助、如何快照）
- `gkd-app/src/commonMain/kotlin/li/gkd/app/util/SimpleRuleSelectors.kt` — 输入到选择器的纯函数
- `gkd-app/src/commonMain/kotlin/li/gkd/app/ui/component/GkQsTileTutorialDialog.kt` — 磁贴教程弹窗（AI 工作台与简易规则共用）
- `gkd-app/src/jvmTest/kotlin/li/gkd/app/util/SimpleRuleSelectorsTest.kt` — 生成的选择器必须通过语法与类型校验

**改动文件**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/ui/subscription/AppConfigPage.kt` — 右下角悬浮按钮展开菜单新增「简易规则 / 完整规则」入口
- `gkd-app/src/commonMain/kotlin/li/gkd/app/ui/subscription/AppConfigViewModel.kt` — 新增 `addSimpleRule(dialogLocator, closeLocator)`，生成规则组并追加到本地订阅
- `gkd-app/src/commonMain/kotlin/li/gkd/app/feature/ai/AiSnapshotPickerPage.kt` — 组件详情字段由「区域」改为「区域bounds」
- `gkd-app/src/commonMain/kotlin/li/gkd/app/ui/home/DashboardPage.kt` — 首页「如何使用李跳跳简易规则」卡片与步骤弹窗
- `gkd-app/src/commonMain/composeResources/values/strings.xml` — `simple_rule_*` / `dashboard_simple_rule_guide_*` 文案

### 12. 无障碍开机自愈

重启后自动写回无障碍服务，避免 ColorOS 等系统移除第三方无障碍条目。

- Plus 的 `BootRestoreReceiver` 监听 `BOOT_COMPLETED` / `QUICKBOOT_POWERON`，在 `enableAutomator && useA11y` 时写回 `enabled_accessibility_services`，带重试与「移除 → 加回」修复；与上游 `BootReceiver`（`fixRestartAutomatorService`）并存，快速开机广播和重试由这里兜底
- `PrivilegeContext.ensureWriteSecureSettings()`：Root 走 `grantRuntimePermission`；Shizuku / ADB 绕过 priv-kit 的 `isPermissionRestricted()` 守卫，由特权服务执行一次 `pm grant`。权限持久化后，开机不再依赖特权服务在线
- 工作模式页文案说明「连接一次特权服务（Shizuku 即可）」

**新增文件**
- `gkd-app/src/androidMain/kotlin/li/gkd/app/receiver/BootReceiver.kt`（上游）
- `gkd-app/src/androidMain/kotlin/li/gkd/app/receiver/BootRestoreReceiver.kt`（Plus）

**改动文件**
- `gkd-android/src/main/AndroidManifest.xml` — `RECEIVE_BOOT_COMPLETED` 权限与两个 receiver
- `gkd-app/src/androidMain/kotlin/li/gkd/app/priv/PrivilegeContext.kt` — `ensureWriteSecureSettings`
- `gkd-app/src/commonMain/composeResources/values{-zh,}/strings.xml` — 工作模式说明文案

### 13. 存储根目录：内部存储 + 一次性迁移

上游 v1.13.1（`77f23ffd`）起，Android 数据根固定为 `app.filesDir`，不再在内部/外部之间二选一。首次启动若有旧外部数据，`ExternalStorageMigration` 先暂存到 `noBackupFilesDir/external-migration`，用 SHA-256 校验后发布到内部；`db` / `store` / `subscription` 先可用，历史目录后台迁移，全部提交后才删除外部原件并写 `.gkd`。v1.13.2（`78412c74`）允许迁移覆盖内部同名文件。

Plus 直接采用这套实现，删除原先的占位识别、自动切回外部和设置页手动恢复。备份导入仍要求 `db.json`：日志包（`db/gkd.db` + `log/`）提示「这是日志压缩包，不是备份文件」，缺 `db.json` 无法恢复订阅。

**改动文件**
- `gkd-app/src/androidMain/kotlin/li/gkd/app/storage/ExternalStorageMigration.kt` — 日志化迁移
- `gkd-app/src/androidMain/kotlin/li/gkd/app/util/AndroidStorage.kt` — 内部根 + `initialize()` 后台迁移历史
- `gkd-app/src/commonMain/kotlin/li/gkd/app/storage/AppStorageLayout.kt` — `HistoryDirectory` 与 `awaitHistoryMigration`
- `gkd-app/src/androidMain/kotlin/li/gkd/app/App.kt` — `onCreate` 先 `AndroidStorage.initialize()`
- 快照 / 崩溃 / 诊断 / 日志导出等待对应历史目录迁移完成

**Plus 保留**
- `gkd-app/src/commonMain/kotlin/li/gkd/app/backup/BackupManager.kt` — `db.json` 必需 + 日志包识别
- `gkd-app/src/commonMain/kotlin/li/gkd/app/storage/FileOperationIssue.kt` — `LogArchive` / `MissingDatabase`
- `gkd-app/src/jvmTest/kotlin/li/gkd/app/backup/BackupImportValidationTest.kt`

> 已被旧版 `.gkd` 误切、内部已有真实数据的设备，官方迁移不会回退外部数据，需要 Root 删标记或恢复备份。

### 14. 磁贴点击后收起控制栏

上游「捕获快照」磁贴靠返回键收起控制栏再截图。ColorOS 17 上返回键收不起控制栏，结果截到的是控制栏本身。Plus 新增 `performActionCollapseShade()`：Android 12+ 先用 `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE`（无障碍或自动化模式均可），失败或低版本再退回返回键。「捕获快照」和「快照按钮」两个磁贴都改用它。

**改动文件**
- `gkd-app/src/androidMain/kotlin/li/gkd/app/matching/action/AndroidGlobalActions.kt` — `performActionCollapseShade`
- `gkd-app/src/androidMain/kotlin/li/gkd/app/priv/AutomationService.kt` — 暴露 `performGlobalAction`
- `gkd-app/src/androidMain/kotlin/li/gkd/app/service/SnapshotTileService.kt`、`ButtonTileService.kt` — 改用收起控制栏

---

## 二、品牌与构建配置

| 项目 | 上游 | GKD-Plus |
|------|------|----------|
| `applicationId` | `li.songe.gkd` | `li.songe.gkd.plus` |
| `versionCode` / `versionName` | 100 / 1.13.0 | 101 / 1.13.0-plus.N |
| 根项目名 | `gkd` | `gkd-plus` |
| 应用名 | `GKD` | `GKD-Plus` |
| APK 文件名 | `gkd-v{ver}.apk` | `gkd-plus-v{ver}.apk` |
| 仓库地址 | `github.com/gkd-kit/gkd` | `github.com/fjjzy/gkd-plus` |

**改动文件**
- `gkd-app/build.gradle.kts`
- `settings.gradle.kts`
- `gkd-app/src/main/res/values/strings.xml`
- `gkd-app/src/commonMain/kotlin/li/gkd/app/util/Constants.kt`（`REPOSITORY_URL`）
- `gkd-app/src/commonMain/kotlin/li/gkd/app/App.kt`（`commitUrl`）

---

## 三、测试

- `gkd-app/src/androidHostTest/kotlin/li/gkd/app/util/AiThinkingUtilsTest.kt` — 校验思考参数按模型名翻译
- `gkd-app/src/androidHostTest/kotlin/li/gkd/app/util/AiMaxTokensUtilsTest.kt` — 校验 Max Tokens 抬底 / 截断翻倍 / 上限回退
- `gkd-app/src/androidHostTest/kotlin/li/gkd/app/util/FolderExtTest.kt` — 校验日志脱敏规则
- `gkd-app/src/androidHostTest/kotlin/li/gkd/app/util/BackupUtilsTest.kt` — 校验备份数据保留真实 apiKey（不被脱敏）

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
