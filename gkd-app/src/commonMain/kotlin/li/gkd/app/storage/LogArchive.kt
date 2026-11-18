package li.gkd.app.storage

import java.io.File
import java.nio.file.Files
import li.gkd.app.logging.LogUtils
import li.gkd.app.util.redactSensitiveText

object LogArchive {
    private val layout
        get() = appStorage()

    private val redactableExtensions = setOf("json", "txt", "log")

    fun build(
        metadata: Map<String, () -> String>,
        additionalDirectories: List<File> = emptyList(),
        diagnosticDirectories: List<File> = emptyList(),
        snapshotDays: Int = 0,
    ): File {
        val directory = StorageMaintenance.temporaryDirectory(layout)
        val metadataDirectory =
            Files.createDirectory(directory.resolve("metadata").toPath()).toFile()

        // 导出前抹掉 API Key / Bearer Token：所有进入压缩包的文本都要先复制再脱敏，不能改动线上数据
        val logs = directory.resolve("log")
        LogUtils.copyLogs(logs)
        logs.redactSensitiveFiles()

        val exported =
            buildList {
                add(logs)
                listOf(layout.store, layout.db, layout.subscription, layout.crash, layout.startupLog)
                    .filter { it.exists() }
                    .forEach { add(copyForExport(directory, it)) }
                additionalDirectories
                    .filter { it.exists() }
                    .forEach { add(copyForExport(directory, it)) }
                exportSnapshots(directory, snapshotDays)?.let(::add)
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
                target.redactSensitiveFiles()
                listOf(target)
            }
        return buildArchive(metadataDirectory, exported + diagnostics)
    }

    /** 复制到导出目录后再脱敏，返回复制后的路径；同名时追加序号，避免覆盖 store/log。 */
    private fun copyForExport(root: File, source: File): File {
        var target = root.resolve(source.name)
        var index = 2
        while (target.exists()) {
            target = root.resolve("${source.name}-$index")
            index++
        }
        if (source.isDirectory) {
            check(source.copyRecursively(target, overwrite = true))
        } else {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
        target.redactSensitiveFiles()
        return target
    }

    /** 附加快照记录（最近 [days] 天），便于排查 AI 规则与快照相关问题。 */
    private fun exportSnapshots(root: File, days: Int): File? {
        if (days <= 0) return null
        val source = layout.snapshot
        if (!source.isDirectory) return null
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val target = root.resolve("snapshot").apply { mkdirs() }
        var copied = false
        source.listFiles().orEmpty().forEach { item ->
            if (item.lastModified() >= cutoff) {
                val dest = target.resolve(item.name)
                if (item.isDirectory) {
                    check(item.copyRecursively(dest, overwrite = true))
                } else {
                    item.copyTo(dest, overwrite = true)
                }
                copied = true
            }
        }
        if (!copied) {
            target.deleteRecursively()
            return null
        }
        target.redactSensitiveFiles()
        return target
    }

    /** 只作用于导出目录里的副本，不改动线上数据。 */
    private fun File.redactSensitiveFiles() {
        if (isDirectory) {
            listFiles()?.forEach { it.redactSensitiveFiles() }
            return
        }
        if (!isFile || extension.lowercase() !in redactableExtensions) return
        val oldText = runCatching { readText() }.getOrNull()
        if (oldText == null) {
            // 读不出来的文件无法确认是否含密钥，宁可整体不进压缩包
            delete()
            return
        }
        val newText = redactSensitiveText(oldText)
        if (newText != oldText) {
            writeText(newText)
        }
    }

    private fun buildArchive(
        metadata: File,
        exportedDirectories: List<File>,
    ): File {
        val files = exportedDirectories.filter { Files.exists(it.toPath()) } + metadata
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
