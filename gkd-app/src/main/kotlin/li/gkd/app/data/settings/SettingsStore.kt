package li.gkd.app.data.settings

import kotlinx.serialization.Serializable
import li.gkd.app.text.UiStrings
import li.gkd.app.META
import li.gkd.app.util.AppGroupOption
import li.gkd.app.util.AppSortOption
import li.gkd.app.util.AiThinkingLevelOption
import li.gkd.app.util.AutomatorModeOption
import li.gkd.app.util.RuleSortOption
import li.gkd.app.util.SnapshotDisplayModeOption
import li.gkd.app.util.UpdateChannelOption
import li.gkd.app.util.UpdateTimeOption

@Serializable
data class AiConfig(
    val protocol: String = "openai",
    val apiUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0f,
    val topP: Float = 1f,
    val maxTokens: Int = 32768,
    /**
     * 服务端拒绝过的 Max Tokens（0 = 还没探到）。
     * 记下来才能避免「抬底 → 被拒 → 回退」每次生成都往返一次。
     */
    val maxTokensCeiling: Int = 0,
    /** 思考等级，取值见 [li.gkd.app.util.AiThinkingLevelOption]；默认自动关闭思考 */
    val thinkingLevel: String = AiThinkingLevelOption.Auto.value,
    /** thinkingLevel 为自定义时，直接合并进请求体的 JSON 片段 */
    val thinkingCustom: String = "",
)

@Serializable
data class SettingsStore(
    val enableAutomator: Boolean = false,
    val automatorMode: Int = AutomatorModeOption.A11yMode.value,
    val enableMatch: Boolean = true,
    val enableStatusService: Boolean = false,
    val excludeFromRecents: Boolean = false,
    val captureScreenshot: Boolean = false,
    val screenshotTargetAppId: String = "",
    val screenshotEventSelector: String = "",
    val httpServerPort: Int = 8888,
    val updateSubsInterval: Long = UpdateTimeOption.Everyday.value,
    val captureVolumeChange: Boolean = false,
    val toastWhenClick: Boolean = true,
    val actionToast: String = META.appName,
    val autoClearMemorySubs: Boolean = false,
    val hideSnapshotStatusBar: Boolean = false,
    val autoSaveSnapshotToDownloads: Boolean = false,
    val enableDarkTheme: Boolean? = null,
    val enableDynamicColor: Boolean = true,
    val useSystemToast: Boolean = false,
    val useCustomNotifText: Boolean = false,
    val customNotifTitle: String = META.appName,
    val customNotifText: String = UiStrings.notification_summary_template,
    val updateChannel: Int = if (META.isBeta) UpdateChannelOption.Beta.value else UpdateChannelOption.Stable.value,
    val appSort: Int = AppSortOption.ByUsedTime.value,
    val showBlockApp: Boolean = true,
    val appRuleSort: Int = RuleSortOption.ByDefault.value,
    val subsAppSort: Int = AppSortOption.ByUsedTime.value,
    val subsCategorySort: Int = AppSortOption.ByUsedTime.value,
    val subsAppShowUninstall: Boolean = false,
    val subsAppGroupType: Int = AppGroupOption.UserGroup.value or AppGroupOption.SystemGroup.value,
    val subsCategoryGroupType: Int = AppGroupOption.UserGroup.value or AppGroupOption.SystemGroup.value,
    val subsAppShowBlock: Boolean = false,
    val subsCategoryShowBlock: Boolean = false,
    val subsExcludeSort: Int = AppSortOption.ByUsedTime.value,
    val subsExcludeShowBlockApp: Boolean = true,
    val subsExcludeShowInnerDisabledApp: Boolean = true,
    val subsPowerWarn: Boolean = true,
    val enableBlockA11yAppList: Boolean = false,
    val blockA11yAppListFollowMatch: Boolean = true,
    val a11yAppSort: Int = AppSortOption.ByUsedTime.value,
    val a11yScopeAppSort: Int = AppSortOption.ByUsedTime.value,
    val appGroupType: Int = (1 shl AppGroupOption.normalObjects.size) - 1,
    val a11yAppGroupType: Int = appGroupType,
    val a11yScopeAppGroupType: Int = appGroupType,
    val subsExcludeAppGroupType: Int = appGroupType,
    val showDisabledRule: Boolean = true,
    val snapshotDisplayMode: Int = SnapshotDisplayModeOption.ByTime.value,
    val aiEnable: Boolean = false,
    val aiConfig: AiConfig = AiConfig(),
    /** 多选组件时「可以用第 N 个指代」的提示已展示次数；提醒两次后不再打扰 */
    val aiMultiSelectHintCount: Int = 0,
) {
    val useA11y get() = automatorMode == AutomatorModeOption.A11yMode.value
    val useAutomation get() = automatorMode == AutomatorModeOption.AutomationMode.value
}
