# GKD-Plus 迁移验收清单

> 用途：每次合并上游后，按本清单逐项确认 plus 功能**不只是文件存在，而是入口可达、开关有消费者、弹窗会被渲染**。
> 背景：2026-11-06 合并 v1.13.0 时，曾出现「简易规则弹窗没渲染」「保活页被上游实现替换」「`aiEnable` 没有消费者」「长按预览更新丢失」等问题，都是编译通过但功能断链。
> 配套：功能差异说明见 `GKD-PLUS-DIFF.md`；本清单只写**怎么验**。

## 维护约定

- 本文档是 plus 功能的**迁移验收基线**，不是一次性说明。
- 新增或修改 plus 功能时，同步更新 `GKD-PLUS-DIFF.md`（功能说明）与本文档（入口、开关消费者、验收命令）。
- 每次合并上游前先读这两份文档；合并后按「二」「三」「四」执行，缺一项即视为迁移未完成。
- 知识库中的同名文档只是镜像，以仓库内文件为准，更新后同步上传。

## 一、通用验收原则

1. **入口可达**：入口出现在预期页面，点击有反应（不是只改了状态没人消费）。
2. **开关有消费者**：`grep` 该设置字段，必须同时出现在「UI 读写」和「业务读取」两侧。
3. **弹窗会渲染**：`showXxxDialog = true` 必须能找到对应的 `if (showXxxDialog) { ... }`。
4. **页面是 plus 版**：fork 独有的页面要对照合并前源码（`git show <merge-base>:<旧路径>`），不要接受上游同名页面的替代。
5. **双端编译**：JVM 目标与 Android 目标都要过；只过 Android 会漏掉 Desktop 的 expect/actual。

## 二、逐项锚点（同步后跑一遍）

### AI

- [ ] 生成核心存在：`AiRuleGenerator.kt` 含 `startAutoRule`、`insertRuleToSubscription`、`callAiApiStream`
- [ ] 自动来源接线：`A11ySnapshotTriggers.kt` 两处使用 `settings.value.aiEnable`，不能硬编码 `true`
- [ ] 手动快速识别：`ButtonService.kt` 的 `quickCapture()` 传 `triggerAiRule = true, recordAiSession = true`
- [ ] 入口路由：`GkAppNavigation.kt` 注册 `AiWorkbenchRoute` / `AiConversationRoute` / `AiSnapshotPickerRoute`
- [ ] 选择页直接生成：`AiSnapshotPickerPage.kt` 的「直接生成」无 `enabled = selectedNodes.isNotEmpty()` 限制
- [ ] 空会话生成：`AiConversationPage.kt` 有空会话 item + `AiConversationVm.generateRule`
- [ ] 重试建议：`RuleNotWorkingSuggestion` 存在，且不再写入 `RETRY_CARD_RULE_NOT_WORKING` 消息
- [ ] 规则定位：`AiMessage.groupKeys` 在入库时写入，点击「已加入本地规则」使用 `focusGroupKey`
- [ ] 配置字段：`AiConfig` 10 个字段 + `SettingsStore.aiEnable` / `aiTileDirectPicker`
- [ ] 提示词一致：`composeResources/files/gkd-rule-generator-prompt.md` 2026-11-17 主动修改过（新增「快照为空」的应对说明：可能是系统/应用屏蔽无障碍，先让用户重截，仍空则判定被屏蔽），此后除非再主动修改才需比对哈希

### UI / 保活

- [ ] 首页入口：`DashboardPage.kt` 有「AI 规则工作台」「GKD 保活引导」，不应再有「手动更新 APP」（已于 2026-11-09 移除，更新走「关于 → 检查更新」）
- [ ] 保活页是 fork 版：自启动 / 电池优化 / 后台运行 / 锁定任务 / 无障碍开机自启五张卡片；**外加上游 985d160f 的「悬浮窗保活」开关**（`store.enableStatusOverlay`，`StatusService` 是唯一消费者，这个开关是唯一写入方）——Plus 的引导页覆盖上游同名 `KeepAlivePage` 时容易把上游这个开关整段弄丢，合并后要专门确认它还在
- [ ] 自启动细节：`IntentUtils.kt` 有 `openAutoStartSettingsByRoot`、`isColorOsBrand`、`isMiuiFamily`、`openSystemSettings`；ColorOS 无 Root 时兜底跳系统设置首页（**不能跳应用详情**，OPPO 的应用详情里没有自启动 / 耗电管理入口）
- [ ] 简易规则：`AppConfigPage.kt` 有 `if (showSimpleRuleDialog) { SimpleRuleDialog(...) }` 渲染块，回调是 `addSimpleRule(dialogLocator, closeLocator)`
- [ ] 简易规则输入：`SimpleRuleDialog.kt` 只有「定位弹窗 / 关闭方式」两个输入框，写法说明收在信息按钮弹出的「填写说明」里，不在输入框上方铺文字
- [ ] 示例切换：「示例（8）」每点一次切换一组，覆盖包含 / + / = / - / & / GLOBAL_ACTION_BACK / 完整 ID / 短 ID / bounds
- [ ] 应用规则页批量删除：`AppConfigPage.kt` 长按菜单有「删除」，只对本地订阅规则可用
- [ ] 自动打开本地订阅：`addSimpleRule` 与 `AiRuleGenerator.insertRuleToSubscription` 写入成功后调用 `requestEnabled(SubscriptionIds.LOCAL, true)`
- [ ] 李跳跳文字语法：`SimpleRuleSelectors.kt` 文字默认生成 `text*=`/`desc*=`（不是精确 `text=`），`+`/`-`/`=`/`&` 与 `GLOBAL_ACTION_BACK` 生效；`SimpleRuleSelectorsTest` 通过
- [ ] 首页引导：`DashboardPage.kt` 的「如何使用李跳跳简易规则」步骤是「应用」页 → 选择应用 → 右下角加号 → 「添加简易规则」
- [ ] 选择器生成：`SimpleRuleSelectors.kt` 的 `buildSimpleRuleMatches` 返回多条 `matches`：定位弹窗按 `&` 拆成多条，关闭控件放最后；`移动&取消&确定` + `=确定` 生成 4 条
- [ ] 磁贴教程复用：`AiWorkbenchPage` 用 `GkQsTileTutorialDialog`，简易规则帮助内联 `GkQsTileTutorialContent`，教程内容只有一份
- [ ] 帮助入口合并：简易规则弹窗只有一个信息入口，bounds 获取步骤与「如何快照？」在同一个弹窗里
- [ ] 快照详情字段：`AiSnapshotPickerPage.kt` 显示「区域bounds」而不是「区域」
- [ ] 版本声明：`AboutPage.kt` 含「版本声明」区块

### 更新

- [ ] 多源：`UpdateClient.kt` 仍并发请求 GitHub / Gitee / npmmirror
- [ ] 版本比较：`UpdateStatus.checkUpdate` 使用 `compareVersionNames`，不要退回合成 `versionCode` 比较
- [ ] 双渠道：`AppLinks.BetaUpdate` 是 `releases` 列表（不是 `latest`），`UpdateClient` 能解析 JSON 数组并挑最新；`StableUpdate` 保持 `releases/latest`
- [ ] beta 排序：`compareVersionNames` 支持 `-beta.N`，`x.y.z-beta.N` < `x.y.z` < `x.y.z-plus.N`
- [ ] 忽略列表：按 `versionName` 记录，不按合成 `versionCode`（1.13.0 之前存的数字是合成 code，读出来匹配不上任何 versionName，等于一次性失效）
- [ ] 兜底源隔离：`release.yml` 的 Gitee / npm 步骤带 `if: !contains(github.ref_name, 'beta')`
- [ ] 版本一致性：`release.yml` 有 tag 与 `output-metadata.json` 的 versionName 校验
- [ ] 特权安装校验：`PackageInstall.android.kt` 按 `versionName` 校验下载的 APK，不要按合成 `versionCode`（GitHub 源的 code 与 APK 实际 code 不同量级）
- [ ] 长按预览：`UpdateStatus.previewUpdate` 存在，`AboutPage` 的「检查更新」使用 `combinedClickable(onLongClick = ...)`
- [ ] 下载降级：GitHub 直链连续无进度时打开 `quarkUrl` 并提示

### 其他

- [ ] 日志脱敏：`LogArchive.build` 对所有进入压缩包的文本（store / subscription / log / crash / 启动日志 / 附加目录 / 诊断 / 快照 / 特权崩溃 / db 表 JSON）先复制再脱敏，不直接改线上文件；**原始 `gkd.db` / `-wal` / `-shm` 不进包**，上游改为导出 db 表为 JSON
- [ ] 日志附加快照：`GkShareLogDialog` 有「附加快照记录」选项（天数单选），`createLogArchive(diagnostics, snapshotDays)` 链路通
- [ ] 备份条目：`SettingsRepository.exportBackupEntries` 含 settings（含 `aiConfig`）、actionCount、block 列表、companionScope、`ai_conversations.json`，恢复时走 `withBackupRestore`
- [ ] 存储根：`AndroidStorage.storage` 使用 `app.filesDir`；`App.onCreate` 先调 `AndroidStorage.initialize()`
- [ ] 一次性迁移：外部旧数据先进 `noBackupFilesDir/external-migration`，`db` / `store` / `subscription` 提交后可用；快照 / 崩溃 / 诊断 / 日志读取前等 `awaitHistoryMigration`
- [ ] 迁移等待覆盖面：`SnapshotStore` 的 `save` / `getMinSnapshot` / `markUploaded` / `replaceScreenshot` / `delete` / `createArchive` / `createUploadArchive` 各自在 `mutationMutex` 内先 await；`createLogArchive` 在 `snapshotDays > 0` 时也 await 快照目录
- [ ] 迁移收尾：历史目录全部 committed 后才删除外部原件；暂存目录删干净后才写内部 `.gkd` 标记（先写标记会让残留暂存永远清不掉）。`journal.json` 与暂存同在 `external-migration` 内，所以「断点续跑」只在 cleanUp 之前成立——但 cleanUp 先删外部原件再删暂存，两个阶段之间中断也不会用旧 db 覆盖内部
- [ ] 备份导入校验：缺 `db.json` 必须报错；日志包（`db/gkd.db`、`log/`）提示「这是日志压缩包，不是备份文件」，不能静默导入 subscription 文件
- [ ] 开机自愈：`BootReceiver` / `fixRestartAutomatorService` 链路存在
- [ ] 磁贴收起控制栏：`SnapshotTileService` / `ButtonTileService` 调 `performActionCollapseShade`，不要被上游的 `performActionBack` 覆盖回去
- [ ] 测试迁移：`AiApiUrlTest`、`AiThinkingUtilsTest`、`AiMaxTokensUtilsTest`、`BackupUtilsTest`、`FolderExtTest`

## 三、一键 grep 检查

```bash
rg -n "settings.value.aiEnable" gkd-app/src/androidMain/kotlin/li/gkd/app/snapshot/A11ySnapshotTriggers.kt
rg -n "SimpleRuleDialog\(" gkd-app/src/commonMain/kotlin/li/gkd/app/ui/subscription/AppConfigPage.kt
rg -n "buildSimpleRuleMatches|simpleRuleAction" gkd-app/src/commonMain/kotlin/li/gkd/app/util/SimpleRuleSelectors.kt
rg -n "GkQsTileTutorialDialog" gkd-app/src/commonMain/kotlin/li/gkd/app
rg -n "fun previewUpdate" gkd-app/src/commonMain/kotlin/li/gkd/app/ui/update/UpdateStatus.kt
rg -n "openAutoStartSettingsByRoot|isMiuiFamily" gkd-app/src/androidMain/kotlin/li/gkd/app/util/IntentUtils.kt
rg -n "GKD 保活引导|keep_alive_guide_title" gkd-app/src/commonMain/composeResources/values/strings.xml gkd-app/src/commonMain/kotlin/li/gkd/app/ui/home/DashboardPage.kt
rg -n "focusGroupKey = targetKey" gkd-app/src/commonMain/kotlin/li/gkd/app/feature/ai/AiConversationPage.kt
rg -n "compareVersionNames" gkd-app/src/commonMain/kotlin/li/gkd/app/ui/update/UpdateStatus.kt
rg -n "BetaUpdate|StableUpdate" gkd-app/src/commonMain/kotlin/li/gkd/app/network/AppLinks.kt
rg -n "is JsonArray" gkd-app/src/commonMain/kotlin/li/gkd/app/network/UpdateClient.kt
rg -n "contains\(github.ref_name, 'beta'\)" .github/workflows/release.yml
rg -n "startAutoRule" gkd-app/src/commonMain/kotlin/li/gkd/app/util/AiRuleGenerator.kt
rg -n "ExternalStorageMigration|awaitHistoryMigration" gkd-app/src/androidMain/kotlin/li/gkd/app/util/AndroidStorage.kt gkd-app/src/commonMain/kotlin/li/gkd/app/storage/AppStorageLayout.kt
rg -n "BackupIssue.LogArchive|BackupIssue.MissingDatabase|looksLikeLogArchive" gkd-app/src/commonMain/kotlin/li/gkd/app/backup/BackupManager.kt
rg -n "performActionCollapseShade" gkd-app/src/androidMain/kotlin/li/gkd/app/service/SnapshotTileService.kt gkd-app/src/androidMain/kotlin/li/gkd/app/service/ButtonTileService.kt
```

任何一条无输出，就说明对应功能在合并中掉了。

## 四、人工可达性检查

1. 首页：三张 plus 卡片都能点开对应页面。
2. AI 工作台：磁贴开关 0→1 弹教程；会话空态有「为我生成规则」。
3. 应用规则：「简易规则」能弹窗；点「示例」把定位/关闭写入输入框；详情按钮能看 bounds 获取步骤；「如何快照？」能弹磁贴教程；提交后规则出现在本地订阅。
4. 首页：「如何使用李跳跳简易规则」卡片能弹出四步说明。
5. 本地规则：点「已加入本地规则」能定位并高亮；删除规则后再点提示「规则已删除」。
6. 设置 → 快照设置：两个自动来源 + 「自动截图后生成规则」开关；关掉后触发不产生 AI 会话。
7. 关于 → 检查更新：长按能弹预览更新弹窗。
8. 关于 → 更新渠道：切到「测试版」先弹不稳定提示；安装 beta 包后默认渠道应为测试版。
9. 保活页：五项卡片与合并前一致。
10. 存储迁移：外部有旧数据时首启把 `db` / `store` / `subscription` 迁到内部并可用；历史目录后台迁移，完成后外部原件被删除且内部出现 `.gkd`。
11. 导入日志包：应提示「这是日志压缩包，不是备份文件」；导入正常 `gkd-backup-*.zip` 应恢复订阅与设置。
12. 升级后首启：不再出现 `Main UI is already registered` 崩溃页。

## 五、历史上漏过的点（重点盯）

- `SimpleRuleDialog` 状态存在但渲染块丢失 → 点击无反应
- 保活页被上游同名页面替换 → 内容和入口都变
- `aiEnable` 只有 UI 读写、没有业务消费者 → 开关无效
- `previewUpdate` 与长按手势丢失 → 测试入口消失
- 首页 plus 卡片丢失 → 入口不可达
- `errorDesc` 改成 `validationError.toString()` → 回灌给模型的错误文案风格变化
- 日志导出脱敏只覆盖 store/log，db/subscription/crash 原样入包
- 日志分享的「附加快照（最近 N 天）」选项丢失
- 列表图标圆角等小样式在迁移中丢失
- 升级后设置/订阅全空并重新弹隐私协议：先查内部 `.gkd` 标记、内部 `db/store/subscription` 和外部旧目录是否还在；官方迁移只处理「外部有数据、内部未固定」的设备，已被旧版误切到内部空目录的设备不会自动回退
- 导入日志包当备份：日志包没有 `db.json`，即使写入 subscription 文件也不会出现在列表；`BackupManager` 现在直接拒绝并提示
- 上游 v1.13.1 已改为内部存储 + 一次性迁移，不要再把 Plus 的占位识别 / 手动恢复塞回去；`MainViewModel` 的 `check` 崩溃修复仍需保留

## 六、合并后固定流程

1. `git merge <upstream>`，冲突先取上游。
2. 对照 `GKD-PLUS-DIFF.md` 重放 plus 功能。
3. 跑完本清单「二」「三」。
4. `./gradlew :gkd-app:compileKotlinJvm` + `:gkd-android:assembleGkdRelease`。
5. 真机过一遍「四」。
6. 把新增的 plus 功能补进 `GKD-PLUS-DIFF.md` 和本清单。
