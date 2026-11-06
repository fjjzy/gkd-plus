package li.gkd.app.util

import li.gkd.app.settings.AiConfig
import li.gkd.app.settings.SettingsStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupUtilsTest {

    @Test
    fun settingsStoreJsonEncoding_keepsApiKeyForBackupData() {
        val apiKey = "sk-backup-should-not-be-masked-1234567890"
        val store = SettingsStore(
            actionToast = "GKD Plus",
            customNotifTitle = "GKD Plus",
            updateChannel = 0,
            aiConfig = AiConfig(
                protocol = "openai",
                apiUrl = "https://example.com/v1",
                apiKey = apiKey,
                model = "test-model",
            )
        )

        val backupStoreText = json.encodeToString(store)

        assertTrue(backupStoreText.contains(apiKey))
        assertFalse(backupStoreText.contains("********"))
    }
}
