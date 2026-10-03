package li.gkd.app.ui.component

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.gkd.app.util.ToastUtils.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import li.gkd.app.text.UiStrings
import li.gkd.app.MainActivity
import li.gkd.app.util.FolderUtils
import li.gkd.app.ui.share.launchUi
import li.gkd.app.util.TimeUtils.throttle

class ShareLogState(
    private val scope: CoroutineScope,
    private val githubUpload: GithubUploadState,
) {
    private val visibleFlow = MutableStateFlow(false)
    private val snapshotDaysFlow = MutableStateFlow(0)

    fun show() {
        visibleFlow.value = true
    }

    private fun dismiss() {
        visibleFlow.value = false
    }

    private fun share(context: MainActivity) {
        dismiss()
        scope.launchUi {
            val logZipFile = withContext(Dispatchers.IO) {
                FolderUtils.buildLogFile(snapshotDaysFlow.value)
            }
            context.shareFile(logZipFile, UiStrings.logs_share_file)
        }
    }

    private fun save(context: MainActivity) {
        dismiss()
        scope.launchUi {
            FolderUtils.withTemporaryZip(
                create = {
                    withContext(Dispatchers.IO) {
                        FolderUtils.buildLogFile(snapshotDaysFlow.value)
                    }
                },
                delete = FolderUtils::deleteSharedFile,
            ) { logZipFile ->
                context.saveFileToDownloads(logZipFile)
            }
        }
    }

    private fun upload() {
        dismiss()
        val item = GithubUploadItem(
            label = UiStrings.logs_title,
            getFile = { FolderUtils.buildLogFile(snapshotDaysFlow.value) },
            showHref = { "http://i.gkd.li/log/${it.id}" },
            releaseFile = FolderUtils::deleteSharedFile,
        )
        if (!githubUpload.startTask(item)) toast(UiStrings.upload_busy)
    }

    private val snapshotRangeOptions = listOf(0, 1, 3, 7)

    @Composable
    fun Render() {
        val visible by visibleFlow.collectAsStateWithLifecycle()
        if (visible) {
            val context = LocalActivity.current as MainActivity
            val snapshotDays by snapshotDaysFlow.collectAsStateWithLifecycle()
            GkDialog(onDismissRequest = ::dismiss) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    val modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                    Text(
                        text = UiStrings.action_share,
                        modifier = Modifier
                            .clickable(onClick = throttle { share(context) })
                            .then(modifier),
                    )
                    Text(
                        text = UiStrings.action_save_to_downloads,
                        modifier = Modifier
                            .clickable(onClick = throttle { save(context) })
                            .then(modifier),
                    )
                    Text(
                        text = UiStrings.upload_generate_link,
                        modifier = Modifier
                            .clickable(onClick = throttle(::upload))
                            .then(modifier),
                    )
                    // 附加快照选择：发送截图记录便于 AI 规则问题排查
                    Text(
                        text = "附加快照（最近 N 天的快照记录）",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 8.dp),
                    )
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        snapshotRangeOptions.forEach { days ->
                            val selected = snapshotDays == days
                            Text(
                                text = if (days == 0) "不附加" else "$days 天",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { snapshotDaysFlow.value = days }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
