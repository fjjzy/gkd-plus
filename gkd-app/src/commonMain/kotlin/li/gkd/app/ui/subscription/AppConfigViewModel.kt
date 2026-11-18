package li.gkd.app.ui.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import li.gkd.app.app.AppInfoRepository
import li.gkd.app.record.TriggerStats
import li.gkd.app.resources.*
import li.gkd.app.rule.RuleGroupConfigService
import li.gkd.app.rule.RuleGroupTarget
import li.gkd.app.rule.RuleSetting
import li.gkd.app.rule.RuleSort
import li.gkd.app.rule.RuleSwitchRequest
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.state.Loadable
import li.gkd.app.subscription.RawSubscription
import li.gkd.app.subscription.SubscriptionJson.toJson5String
import li.gkd.app.subscription.SubscriptionRepository
import li.gkd.app.subscription.prepareAppGroupsForCopy
import li.gkd.app.ui.MainViewModel
import li.gkd.app.ui.component.MultiSelectionState
import li.gkd.app.ui.navigation.AppConfigRoute
import li.gkd.app.ui.state.BaseViewModel
import li.gkd.db.ActionLog
import li.gkd.db.Db
import li.gkd.db.RuleGroupType
import li.gkd.db.RuleTriggerStats
import li.gkd.db.SubsItem
import li.gkd.db.SubscriptionConfigSnapshot
import li.gkd.db.SubscriptionConfigStore
import li.gkd.db.SubscriptionIds
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import li.gkd.app.subscription.SubscriptionEditResult
import li.gkd.app.subscription.SubscriptionInputParser
import li.gkd.app.util.buildSimpleRuleMatches
import li.gkd.app.util.isInvalidSimpleRuleBounds
import li.gkd.app.util.simpleRuleAction

data class AppRuleSubscription(val subsItem: SubsItem, val subscription: RawSubscription)

data class AppConfigUiState(
    val configs: SubscriptionConfigSnapshot,
    val subsPairs: List<Pair<AppRuleSubscription, List<RawSubscription.RawGroupProps>>>,
    val latestLogs: List<ActionLog>,
    val triggerStats: TriggerStats,
)

class AppConfigViewModel(
    val route: AppConfigRoute,
) : BaseViewModel() {
    val selection = MultiSelectionState<RuleGroupTarget>()

    val categorySelection: StateFlow<AppRuleCategorySelection>
        field =
            MutableStateFlow(
                if (route.focusLog == null) AppRuleCategorySelection.Global
                else AppRuleCategorySelection.FocusedRule,
            )

    fun selectCategory(selection: AppRuleCategorySelection) {
        categorySelection.value = selection
    }

    val revealDisabledRules: StateFlow<Boolean>
        field = MutableStateFlow(false)

    fun setRevealDisabledRules(value: Boolean) {
        revealDisabledRules.value = value
    }

    suspend fun removeFromWhitelist() {
        SettingsRepository.updateBlockMatchAppList { it - route.appId }
    }

    suspend fun removeFromPartialDisable() {
        SettingsRepository.updateBlockA11yAppList { it - route.appId }
    }

    fun setRuleSortType(option: RuleSort) {
        SettingsRepository.updateSettings { it.copy(appRuleSort = option.value) }
    }

    fun setShowDisabledRules(show: Boolean) {
        SettingsRepository.updateSettings { it.copy(showDisabledRule = show) }
    }

    /**
     * 简易规则：根据弹窗定位和关闭方式生成一条规则并追加到本地订阅。
     * [closeLocator] 为空时动作为返回键，否则点击匹配到的关闭控件。
     * 返回错误信息；成功时返回 null。
     */
    suspend fun addSimpleRule(dialogLocator: String, closeLocator: String): String? {
        val dialogText = dialogLocator.trim()
        val closeText = closeLocator.trim()
        if (isInvalidSimpleRuleBounds(dialogText) || isInvalidSimpleRuleBounds(closeText)) {
            return Res.string.simple_rule_bounds_invalid.getSync()
        }
        val matches = buildSimpleRuleMatches(dialogText, closeText)
            ?: return Res.string.simple_rule_input_required.getSync()
        val action = simpleRuleAction(closeText)
        val name = dialogText.ifEmpty { closeText }
        val groupJson =
            buildJsonObject {
                put("name", name)
                putJsonArray("rules") {
                    addJsonObject {
                        put("matches", matches)
                        put("action", action)
                    }
                }
            }
        val parsed =
            SubscriptionInputParser.parse(groupJson.toString())
                .flatMap { it.parseAppGroups(route.appId) }
        val group =
            when (parsed) {
                is SubscriptionEditResult.Success -> parsed.value.first()
                is SubscriptionEditResult.Rejected -> return parsed.reason.messageResource.getSync()
            }
        val result =
            SubscriptionRepository.editSubscription(SubscriptionIds.LOCAL) { subscription ->
                subscription.editResult {
                    val app = subscription.getApp(route.appId)
                    val names = app.groups.map { it.name }.toSet()
                    val baseName = group.name
                    var uniqueName = baseName
                    var index = 2
                    while (uniqueName in names) {
                        uniqueName = "$baseName-$index"
                        index += 1
                    }
                    appendAppGroups(
                        targetApp = app,
                        groups = listOf(group.copy(name = uniqueName)),
                    )
                }
            }
        return when (result) {
            is SubscriptionEditResult.Success -> null
            is SubscriptionEditResult.Rejected -> result.reason.messageResource.getSync()
        }
    }

    val uiState: StateFlow<Loadable<AppConfigUiState>> =
        SubscriptionRepository.snapshotFlow
            .flatMapLatest { source ->
                when (source) {
                    Loadable.Loading -> flowOf(Loadable.Loading)
                    is Loadable.Failure -> flowOf(source)
                    is Loadable.Ready ->
                        combine<
                                SubscriptionConfigSnapshot,
                                List<ActionLog>,
                                List<RuleTriggerStats>,
                                Loadable<AppConfigUiState>,
                            >(
                                SubscriptionConfigStore.observe(),
                                Db.actionLogDao.queryLatestByAppId(route.appId),
                                Db.ruleTriggerStatsDao.observe(appId = route.appId),
                            ) { configs, logs, records ->
                                val globalConfigs =
                                    configs.globalGroupConfigs.associateBy {
                                        it.subsId to it.groupKey
                                    }
                                Loadable.Ready(
                                    AppConfigUiState(
                                        configs = configs,
                                        subsPairs =
                                            configs.subsItems
                                                .filter { it.enable }
                                                .sortedBy { it.order }
                                                .mapNotNull { item ->
                                                    val sub =
                                                        source.value.subscriptions[item.id]
                                                            ?: return@mapNotNull null
                                                    val appEnabled =
                                                        configs.appConfigs
                                                            .find {
                                                                it.subsId == item.id &&
                                                                    it.appId == route.appId
                                                            }
                                                            ?.enable != false
                                                    // Parent switches define this summary's scope,
                                                    // even when disabled rules are shown.
                                                    val globalGroups =
                                                        sub.globalGroups.filter { group ->
                                                            globalConfigs[item.id to group.key]
                                                                ?.enable ?: group.enable ?: true
                                                        }
                                                    val groups =
                                                        globalGroups +
                                                            if (appEnabled)
                                                                sub.getAppGroups(route.appId)
                                                            else emptyList()
                                                    (AppRuleSubscription(
                                                            item,
                                                            sub,
                                                        ) to groups)
                                                        .takeIf { groups.isNotEmpty() }
                                                },
                                        latestLogs = logs,
                                        triggerStats = TriggerStats(records),
                                    ),
                                )
                            }
                            .catch { emit(Loadable.Failure(it)) }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, Loadable.Loading)

    fun prepareSwitches(state: AppConfigUiState, targets: Set<RuleGroupTarget>): RuleSwitchRequest =
        RuleGroupConfigService.prepare(
            targets.map { it.toSwitchTarget() },
            state.subsPairs.map { it.first.subscription },
            state.configs,
        )

    suspend fun applySwitches(
        request: RuleSwitchRequest,
        setting: RuleSetting,
        confirmation: String? = null,
    ) =
        applyRuleSwitchWithConfirmation(
            request,
            setting,
            MainViewModel.requireCurrent().dialogRequests,
            RuleSwitchHost.AppRules,
            confirmation,
        )

    suspend fun buildSelectedGroupsText(selectedGroups: Set<RuleGroupTarget>): String =
        withContext(Dispatchers.Default) {
            val selectedKeys =
                selectedGroups.mapTo(mutableSetOf()) {
                    Triple(it.subsId, it.groupType, it.groupKey)
                }
            val subsPairs = uiState.value.value?.subsPairs.orEmpty()
            val selectedAppGroups = subsPairs.flatMap { (entry, groups) ->
                val subsId = entry.subsItem.id
                groups.filterIsInstance<RawSubscription.RawAppGroup>().mapNotNull { group ->
                    if (Triple(subsId, RuleGroupType.App, group.key) in selectedKeys) {
                        subsId to group
                    } else {
                        null
                    }
                }
            }
            check(selectedAppGroups.isNotEmpty()) {
                Res.string.selected_app_rules_no_copyable.getSync()
            }
            toJson5String(
                RawSubscription.RawApp(
                    id = route.appId,
                    name = AppInfoRepository.snapshot?.apps.orEmpty()[route.appId]?.name,
                    groups = prepareAppGroupsForCopy(selectedAppGroups),
                ),
            )
        }
}
