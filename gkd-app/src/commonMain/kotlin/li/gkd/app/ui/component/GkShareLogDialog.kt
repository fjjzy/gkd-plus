package li.gkd.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import li.gkd.app.resources.*
import org.jetbrains.compose.resources.stringResource

private val snapshotDayOptions = listOf(0, 1, 3, 7)

@Composable
fun GkShareLogDialog(
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onUpload: () -> Unit,
    snapshotDays: Int = 0,
    onSnapshotDaysChange: (Int) -> Unit = {},
) {
    GkDialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            val modifier = Modifier.fillMaxWidth().padding(16.dp)
            Text(
                text = stringResource(Res.string.action_share),
                modifier = Modifier.clickable(onClick = onShare).then(modifier),
            )
            Text(
                text = stringResource(Res.string.action_save_to_downloads),
                modifier = Modifier.clickable(onClick = onSave).then(modifier),
            )
            Text(
                text = stringResource(Res.string.upload_generate_link),
                modifier = Modifier.clickable(onClick = onUpload).then(modifier),
            )
            Text(
                text = stringResource(Res.string.logs_attach_snapshot),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp),
            )
            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                snapshotDayOptions.forEach { days ->
                    val selected = snapshotDays == days
                    Text(
                        text =
                            if (days == 0) {
                                stringResource(Res.string.logs_snapshot_none)
                            } else {
                                stringResource(Res.string.logs_snapshot_days, days.toString())
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier =
                            Modifier.clip(MaterialTheme.shapes.small)
                                .clickable { onSnapshotDaysChange(days) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
