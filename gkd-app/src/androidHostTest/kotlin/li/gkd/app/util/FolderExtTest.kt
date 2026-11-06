package li.gkd.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderExtTest {

    @Test
    fun redactSensitiveText_masksApiKeysInExportedLogs() {
        val text = """
            {
              "protocol": "openai",
              "apiKey": "sk-json-openai-1234567890",
              "api_key": "sk-json-snake-1234567890",
              "api-key": "sk-json-kebab-1234567890",
              "model": "test-model"
            }
            apiKey=sk-plain-equals-1234567890
            api key: sk-plain-space-1234567890
            x-api-key: sk-header-x-api-key-1234567890
            Authorization: Bearer sk-bearer-token-1234567890
            other=value
        """.trimIndent()

        val redacted = redactSensitiveText(text)

        assertFalse(redacted.contains("sk-json-openai-1234567890"))
        assertFalse(redacted.contains("sk-json-snake-1234567890"))
        assertFalse(redacted.contains("sk-json-kebab-1234567890"))
        assertFalse(redacted.contains("sk-plain-equals-1234567890"))
        assertFalse(redacted.contains("sk-plain-space-1234567890"))
        assertFalse(redacted.contains("sk-header-x-api-key-1234567890"))
        assertFalse(redacted.contains("sk-bearer-token-1234567890"))
        assertTrue(redacted.contains("\"apiKey\": \"********\""))
        assertTrue(redacted.contains("\"api_key\": \"********\""))
        assertTrue(redacted.contains("\"api-key\": \"********\""))
        assertTrue(redacted.contains("apiKey=********"))
        assertTrue(redacted.contains("api key: ********"))
        assertTrue(redacted.contains("x-api-key: ********"))
        assertTrue(redacted.contains("Authorization: Bearer ********"))
        assertTrue(redacted.contains("other=value"))
    }

    @Test
    fun redactSensitiveText_keepsSafeTextUnchanged() {
        val text = "model=test\napiUrl=https://example.com/v1\nmessage=no secret"

        assertEquals(text, redactSensitiveText(text))
    }
}
