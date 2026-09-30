package li.gkd.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import li.gkd.app.text.UiStrings
import li.gkd.app.ui.component.GkAlertDialog
import li.gkd.app.ui.component.GkTextSwitch

/**
 * 简易规则弹窗：输入用于定位弹窗的文字 / 控件 ID，并选择是否通过返回键关闭。
 */
@Composable
fun SimpleRuleDialog(
    onDismissRequest: () -> Unit,
    onSubmit: (text: String, id: String, backToClose: Boolean) -> Unit,
) {
    var textValue by remember { mutableStateOf("") }
    var idValue by remember { mutableStateOf("") }
    var backToClose by remember { mutableStateOf(false) }
    var showExample by remember { mutableStateOf(false) }

    GkAlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(text = UiStrings.simple_rule_title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    label = { Text(text = UiStrings.simple_rule_text_label) },
                    value = textValue,
                    placeholder = { Text(text = UiStrings.simple_rule_text_hint) },
                    onValueChange = { textValue = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    label = { Text(text = UiStrings.simple_rule_id_label) },
                    value = idValue,
                    placeholder = { Text(text = UiStrings.simple_rule_id_hint) },
                    onValueChange = { idValue = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                GkTextSwitch(
                    title = UiStrings.simple_rule_back_close,
                    subtitle = UiStrings.simple_rule_back_close_desc,
                    checked = backToClose,
                    onCheckedChange = { backToClose = it },
                )
                if (showExample) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = UiStrings.simple_rule_example_title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = UiStrings.simple_rule_example_content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = textValue.isNotBlank() || idValue.isNotBlank(),
                onClick = { onSubmit(textValue, idValue, backToClose) },
            ) {
                Text(text = UiStrings.simple_rule_add)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = UiStrings.simple_rule_cancel)
            }
            TextButton(onClick = { showExample = !showExample }) {
                Text(text = UiStrings.simple_rule_example)
            }
        },
    )
}
