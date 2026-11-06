package li.gkd.app.feature.snapshot

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import li.gkd.app.ui.component.GkAlertDialog

@Composable
fun GenerateAiRuleDialog(
    onDismissRequest: () -> Unit,
    onDirectGenerate: () -> Unit,
    onConversationGenerate: () -> Unit,
) {
    GkAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = "生成 AI 规则") },
        text = { Text(text = "AI 将基于该快照的节点树生成关闭/点击规则") },
        confirmButton = {
            TextButton(onClick = onDirectGenerate) {
                Text(text = "直接生成")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onConversationGenerate) {
                    Text(text = "对话生成")
                }
                TextButton(onClick = onDismissRequest) {
                    Text(text = "算了")
                }
            }
        },
    )
}
