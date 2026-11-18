package li.gkd.app.storage

import java.io.File
import kotlinx.coroutines.flow.first
import li.gkd.app.logging.LogUtils
import li.gkd.app.matching.diagnostics.MatchDiagnosticFiles
import li.gkd.app.util.JsonUtils
import li.gkd.db.Db
import li.gkd.db.MatchDiagnosticRecord

data class LogArchiveInputs(
    val metadata: Map<String, () -> String>,
    val privilegeCrashFiles: List<File> = emptyList(),
)

expect fun logArchiveInputs(): LogArchiveInputs

/** An empty selection includes all saved diagnostic records. */
suspend fun createLogArchive(
    diagnostics: List<MatchDiagnosticRecord> = emptyList(),
    snapshotDays: Int = 0,
): File {
    val storage = appStorage()
    storage.awaitHistoryMigration(HistoryDirectory.Log)
    storage.awaitHistoryMigration(HistoryDirectory.Crash)
    // 附加快照时 LogArchive 会直接读快照目录，同样要等它迁完
    if (snapshotDays > 0) {
        storage.awaitHistoryMigration(HistoryDirectory.Snapshot)
    }
    return MatchDiagnosticFiles.withSavedFiles {
        val inputs = logArchiveInputs()
        val records = diagnostics.ifEmpty { Db.matchDiagnosticRecordDao.observeAll().first() }
        LogArchive.build(
            inputs.metadata +
                ("log-writer.json" to { JsonUtils.keepingNulls.encodeToString(LogUtils.status()) }),
            inputs.privilegeCrashFiles,
            records.map { MatchDiagnosticFiles.directory(it) },
            snapshotDays,
        )
    }
}
