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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import li.gkd.app.data.settings.AiConfig
import li.gkd.app.store.AppStore
import li.gkd.app.ui.component.GkAlertDialog
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkSizedIconButton
import li.gkd.app.util.AiRuleGenerator
import li.gkd.app.util.TimeUtils.throttle
import li.gkd.app.util.ToastUtils.copyText
import li.gkd.app.util.ToastUtils.toast
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
    var modelList by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelListLoading by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testLoading by remember { mutableStateOf(false) }
    // 已通过测试的完整配置；点确定时若当前配置与之一致则直接保存
    var testedConfig by remember { mutableStateOf<AiConfig?>(null) }
    val scope = rememberCoroutineScope()

    fun currentConfig(temp: Float? = null, topP: Float? = null, maxTokens: Int? = null) = aiConfig.copy(
        protocol = protocolValue,
        apiUrl = apiUrlValue,
        apiKey = apiKeyValue,
        model = modelValue,
        temperature = temp ?: temperatureValue.toFloatOrNull() ?: aiConfig.temperature,
        topP = topP ?: topPValue.toFloatOrNull() ?: aiConfig.topP,
        maxTokens = maxTokens ?: maxTokensValue.toIntOrNull() ?: aiConfig.maxTokens,
    )

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
                )
            )
        }
        toast("AI 配置已保存")
        onDismissRequest()
    }

    fun onFailedTest(e: Throwable) {
        val reason = AiRuleGenerator.analyzeConnectionError(e)
        val fullError = "AI 规则配置连接测试失败：\n${e.message ?: e.toString()}\n\n分析：$reason"
        // 错误信息已复制到剪贴板，方便去问其他 AI
        copyText(fullError)
        testResult = "失败：$reason（详情已复制）"
        toast("连接测试失败：$reason\n（错误详情已复制到剪贴板）")
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
                                    toast("获取模型列表失败：${e.message}")
                                }
                            }
                        },
                    )
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
