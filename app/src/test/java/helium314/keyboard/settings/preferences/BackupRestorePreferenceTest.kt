package helium314.keyboard.settings.preferences

import helium314.keyboard.latin.settings.Settings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupRestorePreferenceTest {
    @Test
    fun backupSettingsExcludeSensitiveKeys() {
        val snapshot = parseSettingsSnapshot(
            settingsToJsonString(
                mapOf(
                    Settings.PREF_OPENROUTER_API_KEY to "secret",
                    Settings.PREF_PAYPERQ_API_KEY to "secret-too",
                    "pinned_clips" to """[{"content":"should not persist"}]""",
                    "safe_string" to "value",
                    "safe_boolean" to true,
                )
            ).split("\n")
        )

        assertFalse(snapshot.strings.containsKey(Settings.PREF_OPENROUTER_API_KEY))
        assertFalse(snapshot.strings.containsKey(Settings.PREF_PAYPERQ_API_KEY))
        assertFalse(snapshot.strings.containsKey("pinned_clips"))
        assertEquals("value", snapshot.strings["safe_string"])
        assertEquals(true, snapshot.booleans["safe_boolean"])
    }

    @Test
    fun restoreSettingsRejectsSensitiveKeysFromLegacyOrCraftedBackup() {
        val snapshot = parseSettingsSnapshot(
            listOf(
                "string settings",
                """{"${Settings.PREF_OPENROUTER_API_KEY}":"secret","${Settings.PREF_PAYPERQ_API_KEY}":"secret-too","safe_string":"value"}""",
                "string set settings",
                """{"pinned_clips":["private clip"],"safe_set":["entry"]}""",
            )
        )

        assertFalse(snapshot.strings.containsKey(Settings.PREF_OPENROUTER_API_KEY))
        assertFalse(snapshot.strings.containsKey(Settings.PREF_PAYPERQ_API_KEY))
        assertFalse(snapshot.stringSets.containsKey("pinned_clips"))
        assertEquals("value", snapshot.strings["safe_string"])
        assertEquals(setOf("entry"), snapshot.stringSets["safe_set"])
    }

    @Test
    fun backupFileAllowlistExcludesLearnedAndHistoryData() {
        assertTrue(isAllowedBackupFile("blacklists/example.txt"))
        assertTrue(isAllowedBackupFile("custom_background_image.jpg"))
        assertFalse(isAllowedBackupFile("dicts/en/example.user.dict"))
        assertFalse(isAllowedBackupFile("UserHistoryDictionary.en/UserHistoryDictionary.en.body"))
    }

    @Test
    fun backupEntryNormalizationRejectsTraversal() {
        assertEquals("safe/path.txt", normalizeBackupEntryName("/safe/path.txt"))
        assertFailsWith<IllegalArgumentException> {
            normalizeBackupEntryName("../unsafe.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeBackupEntryName("safe/../unsafe.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeBackupEntryName("..")
        }
    }

    @Test
    fun restoreEntryCopyDoesNotCloseZipStream() {
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("blacklists/first.txt"))
                zip.write("first".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("blacklists/second.txt"))
                zip.write("second".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val parentDir = Files.createTempDirectory("restore-test-").toFile()

        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                assertEquals("blacklists/first.txt", zip.nextEntry.name)
                copyRestoreEntryToNewFile(zip, parentDir.resolve("first.txt"))
                zip.closeEntry()

                assertEquals("blacklists/second.txt", zip.nextEntry.name)
                copyRestoreEntryToNewFile(zip, parentDir.resolve("second.txt"))
                zip.closeEntry()
            }
            assertEquals("first", parentDir.resolve("first.txt").readText())
            assertEquals("second", parentDir.resolve("second.txt").readText())
        } finally {
            parentDir.deleteRecursively()
        }
    }
}
