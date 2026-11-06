package li.gkd.app.storage

import java.io.File
import java.nio.file.Files
import li.gkd.app.logging.LogUtils
import li.gkd.app.util.redactSensitiveText

object LogArchive {
    private val layout
        get() = appStorage()

    fun build(
        metadata: Map<String, () -> String>,
        additionalDirectories: List<File> = emptyList(),
        diagnosticDirectories: List<File> = emptyList(),
    ): File {
        val directory = StorageMaintenance.temporaryDirectory(layout)
        val metadataDirectory =
            Files.createDirectory(directory.resolve("metadata").toPath()).toFile()
        val logs = directory.resolve("log")
        LogUtils.copyLogs(logs)
        // 导出前抹掉 API Key / Bearer Token，避免日志或配置外泄
        val store = directory.resolve("store")
        layout.store.copyRecursively(store, overwrite = true)
        listOf(store, logs).forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("json", "txt", "log") }
                .forEach { file -> file.writeText(redactSensitiveText(file.readText())) }
        }
        metadata.forEach { (name, content) ->
            metadataDirectory.resolve(name).writeText(content())
        }
        val diagnostics =
            if (diagnosticDirectories.isEmpty()) emptyList()
            else {
                val target = directory.resolve("matching-diagnostics").apply { mkdirs() }
                diagnosticDirectories.forEach { source ->
                    check(source.copyRecursively(target.resolve(source.name)))
                }
                listOf(target)
            }
        return buildArchive(metadataDirectory, logs, store, additionalDirectories + diagnostics)
    }

    private fun buildArchive(
        metadata: File,
        logs: File,
        redactedStore: File,
        additionalDirectories: List<File>,
    ): File {
        val files =
            (listOf(
                    layout.db,
                    redactedStore,
                    layout.subscription,
                    logs,
                    layout.crash,
                    layout.startupLog,
                ) + additionalDirectories)
                .filter { directory -> !Files.notExists(directory.toPath()) } + metadata
        val archive =
            ExportFileNames.reserve(
                layout.sharedCache,
                "gkd-log-${ExportFileNames.timestamp(System.currentTimeMillis())}",
                "zip",
            )
        ZipArchive.zipFiles(files, archive, includeEmptyDirectories = false)
        return archive
    }
}
