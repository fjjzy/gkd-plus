package li.gkd.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.gkd.app.data.settings.AiConfig
import li.gkd.app.store.AppStore
import li.gkd.app.ui.component.GkAlertDialog
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkSizedIconButton
import li.gkd.app.util.AiMaxTokensUtils
import li.gkd.app.util.AiModelCatalogUtils
import li.gkd.app.util.AiRuleGenerator
import li.gkd.app.util.AiThinkingLevelOption
import li.gkd.app.util.AiThinkingUtils
import li.gkd.app.util.TimeUtils.throttle
import li.gkd.app.util.ToastUtils.toast
import li.gkd.app.util.findOption
import li.gkd.app.util.launchLogged

@Composable
fun AiSettingsDialog(
    aiConfig: AiConfig,
    onDismissRequest: () -> Unit,
) {
    var protocolValue by remember(aiConfig) { mutableStateOf(aiConfig.protocol) }
    var apiUrlValue by remember(aiConfig) { mutableStateOf(aiConfig.apiUrl) }
    var apiKeyValue by remember(aiConfig) { mutableStateOf(aiConfig.apiKey) }
    var modelValue by remember(aiConfig) { mutableStateOf(aiConfig.model) }
    var temperatureValue by remember(aiConfig) { mutableStateOf(aiConfig.temperature.toString()) }
    var topPValue by remember(aiConfig) { mutableStateOf(aiConfig.topP.toString()) }
    var maxTokensValue by remember(aiConfig) { mutableStateOf(aiConfig.maxTokens.toString()) }
    var thinkingLevelValue by remember(aiConfig) { mutableStateOf(aiConfig.thinkingLevel) }
    var thinkingCustomValue by remember(aiConfig) { mutableStateOf(aiConfig.thinkingCustom) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var thinkingMenuExpanded by remember { mutableStateOf(false) }
    var modelList by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelListLoading by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testLoading by remember { mutableStateOf(false) }
    // 已通过测试的完整配置；点确定时若当前配置与之一致则直接保存
    var testedConfig by remember { mutableStateOf<AiConfig?>(null) }
    val scope = rememberCoroutineScope()
    // models.dev 能力表：内置表认不出模型时用它补一句说明。
    // 顺带把它当 remember key，拉到之后让下面的预览文案重算。
    val capabilityCatalog by AiModelCatalogUtils.catalogFlow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { AiModelCatalogUtils.refresh() }

    fun currentConfig(
        temp: Float? = null,
        topP: Float? = null,
        maxTokens: Int? = null,
    ) = aiConfig.copy(
        protocol = protocolValue,
        apiUrl = apiUrlValue,
        apiKey = apiKeyValue,
        model = modelValue,
        temperature = temp ?: temperatureValue.toFloatOrNull() ?: aiConfig.temperature,
        topP = topP ?: topPValue.toFloatOrNull() ?: aiConfig.topP,
        maxTokens = maxTokens ?: maxTokensValue.toIntOrNull() ?: aiConfig.maxTokens,
        thinkingLevel = thinkingLevelValue,
        thinkingCustom = thinkingCustomValue,
    )

    // 能力表拉到后重算一次，让「将发送」的说明带上 models.dev 的补充
    val thinkingPreview = remember(
        aiConfig,
        modelValue,
        thinkingLevelValue,
        thinkingCustomValue,
        capabilityCatalog,
    ) {
        AiThinkingUtils.describe(currentConfig())
    }

    fun saveConfig(config: AiConfig) {
        AppStore.updateSettings {
            it.copy(
                aiConfig = it.aiConfig.copy(
                    protocol = config.protocol,
                    apiUrl = config.apiUrl.trim().trimEnd('/'),
                    apiKey = config.apiKey.trim(),
                    model = config.model.trim(),
                    temperature = config.temperature,
                    topP = config.topP,
                    maxTokens = config.maxTokens,
                    thinkingLevel = config.thinkingLevel,
                    thinkingCustom = config.thinkingCustom,
                )
            )
        }
        toast("AI 配置已保存")
        onDismissRequest()
    }

    fun onFailedTest(e: Throwable) {
        val reason = AiRuleGenerator.analyzeConnectionError(e)
        // toast 只说看得懂的原因，完整报错进剪贴板（方便拿去问其它 AI 或提 issue）
        AiRuleGenerator.copyFailureToClipboard("AI 规则配置连接测试失败", e, reason)
        testResult = "失败：$reason（详情已复制）"
        toast("连接测试失败：$reason（完整报错已复制）")
    }

    GkAlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(text = "AI 规则设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // Protocol selector
                Text(text = "协议", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("openai", "anthropic").forEach { proto ->
                        TextButton(
                            onClick = { protocolValue = proto },
                            modifier = Modifier.weight(1f),
                            colors = if (protocolValue == proto) {
                                ButtonDefaults.textButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                )
                            } else {
                                ButtonDefaults.textButtonColors()
                            },
                        ) {
                            Text(
                                text = proto.replaceFirstChar { it.uppercase() },
                                color = if (protocolValue == proto) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    label = { Text("API 地址") },
                    value = apiUrlValue,
                    placeholder = {
                        Text(
                            text = if (protocolValue == "openai") {
                                "https://api.openai.com"
                            } else {
                                "https://api.anthropic.com"
                            }
                        )
                    },
                    onValueChange = { apiUrlValue = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    label = { Text("API Key") },
                    value = apiKeyValue,
                    placeholder = { Text(text = "sk-...（以 sk- 开头）") },
                    onValueChange = { apiKeyValue = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            label = { Text("模型") },
                            value = modelValue,
                            placeholder = { Text(text = "模型名称(推荐 flash 模型)") },
                            onValueChange = {
                                modelValue = it
                                modelMenuExpanded = false
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                if (modelListLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                } else if (modelList.isNotEmpty()) {
                                    GkIcon(
                                        imageVector = GkIcons.UnfoldMore,
                                        modifier = Modifier.clickable { modelMenuExpanded = true },
                                    )
                                }
                            },
                        )
                        DropdownMenu(
                            expanded = modelMenuExpanded && modelList.isNotEmpty(),
                            onDismissRequest = { modelMenuExpanded = false },
                        ) {
                            modelList.forEach { m ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = m,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (m == modelValue) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                LocalContentColor.current
                                            },
                                        )
                                    },
                                    onClick = {
                                        modelValue = m
                                        modelMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    GkSizedIconButton(
                        size = 40.dp,
                        iconSize = 22.dp,
                        imageVector = GkIcons.Autorenew,
                        contentDescription = "获取模型列表",
                        onClickLabel = "获取模型列表",
                        onClick = throttle {
                            if (apiUrlValue.isBlank() || apiKeyValue.isBlank()) {
                                toast("请先填写 API 地址和 Key")
                                return@throttle
                            }
                            modelListLoading = true
                            scope.launchLogged {
                                val result = AiRuleGenerator.fetchModelList(currentConfig())
                                modelListLoading = false
                                result.onSuccess { list ->
                                    modelList = list
                                    if (list.isNotEmpty() && modelValue.isBlank()) {
                                        modelValue = list.first()
                                    }
                                    modelMenuExpanded = list.isNotEmpty()
                                    toast("获取到 ${list.size} 个模型")
                                }.onFailure { e ->
                                    val reason = AiRuleGenerator.analyzeConnectionError(e)
                                    AiRuleGenerator.copyFailureToClipboard("获取模型列表失败", e, reason)
                                    toast("获取模型列表失败：$reason（完整报错已复制）")
                                }
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 高级设置：默认收起，思考等级和采样参数不该干扰主流程
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { advancedExpanded = !advancedExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    GkIcon(
                        imageVector = if (advancedExpanded) GkIcons.ExpandLess else GkIcons.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(text = "高级设置", style = MaterialTheme.typography.labelLarge)
                }
                if (advancedExpanded) {
                    Text(text = "思考等级", style = MaterialTheme.typography.labelMedium)
                    Box {
                        TextButton(onClick = { thinkingMenuExpanded = true }) {
                            Text(text = AiThinkingLevelOption.objects.findOption(thinkingLevelValue).label)
                            GkIcon(
                                imageVector = GkIcons.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = thinkingMenuExpanded,
                            onDismissRequest = { thinkingMenuExpanded = false },
                        ) {
                            AiThinkingLevelOption.objects.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (option.value == thinkingLevelValue) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                LocalContentColor.current
                                            },
                                        )
                                    },
                                    onClick = {
                                        thinkingLevelValue = option.value
                                        thinkingMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        text = thinkingPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (thinkingLevelValue == AiThinkingLevelOption.Custom.value) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            label = { Text("自定义请求参数 (JSON)") },
                            value = thinkingCustomValue,
                            placeholder = { Text(text = "{\"thinking\":{\"type\":\"disabled\"}}") },
                            onValueChange = { thinkingCustomValue = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                        AiThinkingUtils.validateCustom(thinkingCustomValue)?.let { hint ->
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            label = { Text("Temperature") },
                            value = temperatureValue,
                            onValueChange = { temperatureValue = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        OutlinedTextField(
                            label = { Text("Top P") },
                            value = topPValue,
                            onValueChange = { topPValue = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        label = { Text("Max Tokens") },
                        value = maxTokensValue,
                        onValueChange = { maxTokensValue = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(
                        text = buildString {
                            append("低于 ${AiMaxTokensUtils.MIN_MAX_TOKENS} 会在生成时自动抬到该值并保存")
                            if (aiConfig.maxTokensCeiling > 0) {
                                append("\n服务端上限：${aiConfig.maxTokensCeiling}（曾被拒，不再上调）")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Test button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = throttle {
                            if (apiUrlValue.isBlank() || apiKeyValue.isBlank() || modelValue.isBlank()) {
                                toast("请先填写完整配置")
                                return@throttle
                            }
                            testLoading = true
                            testResult = null
                            scope.launchLogged {
                                val config = currentConfig()
                                val result = AiRuleGenerator.testConnection(config)
                                testLoading = false
                                result.onSuccess {
                                    testedConfig = config
                                    testResult = "连接成功"
                                    toast("连接测试成功")
                                }.onFailure { e ->
                                    testedConfig = null
                                    onFailedTest(e)
                                }
                            }
                        },
                        enabled = !testLoading,
                    ) {
                        if (testLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(text = "测试连接")
                    }
                    testResult?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("失败")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = !testLoading,
                onClick = throttle {
                    val temp = temperatureValue.toFloatOrNull()
                    val topP = topPValue.toFloatOrNull()
                    val maxTokens = maxTokensValue.toIntOrNull()
                    if (temp == null || topP == null || maxTokens == null) {
                        toast("参数格式错误")
                        return@throttle
                    }
                    if (apiUrlValue.isBlank() || apiKeyValue.isBlank() || modelValue.isBlank()) {
                        toast("请先填写完整配置")
                        return@throttle
                    }
                    val config = currentConfig(temp, topP, maxTokens)
                    AiRuleGenerator.validateAiConfig(config)?.let { hint ->
                        toast(hint)
                    }
                    if (testedConfig == config) {
                        // 已测试通过且配置未变，直接保存
                        saveConfig(config)
                        return@throttle
                    }
                    // 未测试（或配置改过）：先自动测试，成功才保存
                    testLoading = true
                    testResult = null
                    scope.launchLogged {
                        val result = AiRuleGenerator.testConnection(config)
                        testLoading = false
                        result.onSuccess {
                            testedConfig = config
                            saveConfig(config)
                        }.onFailure { e ->
                            testedConfig = null
                            onFailedTest(e)
                        }
                    }
                },
            ) {
                if (testLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(text = "确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = "取消")
            }
        },
    )
}
