package li.gkd.app.backup

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import li.gkd.app.DesktopStorage
import li.gkd.app.storage.BackupIssue
import li.gkd.app.storage.FileSource
import li.gkd.app.storage.StorageException
import li.gkd.app.storage.appStorage

class BackupImportValidationTest {
    private suspend fun withArchive(
        entries: Map<String, ByteArray>,
        block: suspend (File) -> Unit,
    ) {
        val directory = Files.createTempDirectory("gkd-backup-validation").toFile()
        try {
            val archive = directory.resolve("archive.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            block(archive)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun logArchiveIsRejectedBeforeTouchingData() = runBlocking {
        System.setProperty(
            "gkd.projectRoot",
            System.getProperty("gkd.projectRoot", File("..").canonicalPath),
        )
        DesktopStorage.initialize(test = true)
        appStorage().tempCache.mkdirs()
        withArchive(
            mapOf(
                "db/gkd.db" to byteArrayOf(1, 2, 3),
                "log/gkd-20261107.log" to "log".encodeToByteArray(),
                "subscription/-2.json" to
                    """{"id":-2,"name":"本地订阅","version":3}""".encodeToByteArray(),
            )
        ) { archive ->
            val error =
                assertFailsWith<StorageException> {
                    BackupManager.importData(FileSource.Local(archive))
                }
            assertEquals(BackupIssue.LogArchive, error.issue)
        }
    }

    @Test
    fun archiveWithoutDatabaseIsRejected() = runBlocking {
        System.setProperty(
            "gkd.projectRoot",
            System.getProperty("gkd.projectRoot", File("..").canonicalPath),
        )
        DesktopStorage.initialize(test = true)
        appStorage().tempCache.mkdirs()
        withArchive(
            mapOf(
                "subscription/-2.json" to
                    """{"id":-2,"name":"本地订阅","version":3}""".encodeToByteArray(),
            )
        ) { archive ->
            val error =
                assertFailsWith<StorageException> {
                    BackupManager.importData(FileSource.Local(archive))
                }
            assertEquals(BackupIssue.MissingDatabase, error.issue)
        }
    }
}
