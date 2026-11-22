package li.gkd.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import li.gkd.app.resources.*
import li.gkd.app.ui.component.GkAlertDialog
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkQsTileTutorialContent
import org.jetbrains.compose.resources.stringResource

/**
 * 简易规则弹窗：输入用于定位弹窗的匹配条件，以及关闭弹窗的方式。
 * 关闭方式留空时动作为返回键；否则点击匹配到的关闭控件。
 */
@Composable
fun SimpleRuleDialog(
    onDismissRequest: () -> Unit,
    onSubmit: (dialogLocator: String, closeLocator: String) -> Unit,
) {
    var dialogValue by remember { mutableStateOf("") }
    var closeValue by remember { mutableStateOf("") }
    var showBoundsHelp by remember { mutableStateOf(false) }
    var exampleIndex by remember { mutableStateOf(0) }
    val examples =
        listOf(
            stringResource(Res.string.simple_rule_example_1_locator) to
                stringResource(Res.string.simple_rule_example_1_close),
            stringResource(Res.string.simple_rule_example_2_locator) to
                stringResource(Res.string.simple_rule_example_2_close),
            stringResource(Res.string.simple_rule_example_3_locator) to
                stringResource(Res.string.simple_rule_example_3_close),
            stringResource(Res.string.simple_rule_example_4_locator) to
                stringResource(Res.string.simple_rule_example_4_close),
            stringResource(Res.string.simple_rule_example_5_locator) to
                stringResource(Res.string.simple_rule_example_5_close),
            stringResource(Res.string.simple_rule_example_6_locator) to
                stringResource(Res.string.simple_rule_example_6_close),
            stringResource(Res.string.simple_rule_example_7_locator) to
                stringResource(Res.string.simple_rule_example_7_close),
            stringResource(Res.string.simple_rule_example_8_locator) to
                stringResource(Res.string.simple_rule_example_8_close),
        )

    if (showBoundsHelp) {
        SimpleRuleBoundsHelpDialog(onDismiss = { showBoundsHelp = false })
    }

    GkAlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(text = stringResource(Res.string.simple_rule_title)) },
        text = {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                // 写法说明收进信息按钮，弹窗本身只留两个输入框
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(Res.string.simple_rule_locator_label),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { showBoundsHelp = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        GkIcon(
                            imageVector = GkIcons.Info,
                            contentDescription =
                                stringResource(Res.string.simple_rule_bounds_help_open),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = dialogValue,
                    onValueChange = { dialogValue = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(Res.string.simple_rule_close_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = closeValue,
                    onValueChange = { closeValue = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = dialogValue.isNotBlank() || closeValue.isNotBlank(),
                onClick = { onSubmit(dialogValue, closeValue) },
            ) {
                Text(text = stringResource(Res.string.simple_rule_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(Res.string.simple_rule_cancel))
            }
            TextButton(
                onClick = {
                    val (locator, close) = examples[exampleIndex % examples.size]
                    dialogValue = locator
                    closeValue = close
                    exampleIndex = (exampleIndex + 1) % examples.size
                },
            ) {
                Text(
                    text =
                        stringResource(
                            Res.string.simple_rule_example,
                            examples.size.toString(),
                        )
                )
            }
        },
    )
}

/** 填写说明：文字写法、ID、bounds 获取步骤和如何快照都放在这一个弹窗里 */
@Composable
private fun SimpleRuleBoundsHelpDialog(onDismiss: () -> Unit) {
    GkAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(Res.string.simple_rule_bounds_help_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.simple_rule_syntax_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                listOf(
                        Res.string.simple_rule_syntax_contains,
                        Res.string.simple_rule_syntax_prefix,
                        Res.string.simple_rule_syntax_suffix,
                        Res.string.simple_rule_syntax_exact,
                        Res.string.simple_rule_syntax_and,
                        Res.string.simple_rule_syntax_id,
                        Res.string.simple_rule_syntax_back,
                    )
                    .forEach {
                        Text(
                            text = stringResource(it),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(Res.string.simple_rule_bounds_section),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(Res.string.simple_rule_bounds_help_step1),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Res.string.simple_rule_bounds_help_step2),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Res.string.simple_rule_bounds_help_step3),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Res.string.simple_rule_bounds_help_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(Res.string.simple_rule_snapshot_help),
                    style = MaterialTheme.typography.titleSmall,
                )
                GkQsTileTutorialContent()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(Res.string.action_understood))
            }
        },
    )
}
