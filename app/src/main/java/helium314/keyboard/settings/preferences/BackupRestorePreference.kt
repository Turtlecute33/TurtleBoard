// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.filePicker
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import androidx.core.content.edit
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.transferOldPinnedClips

@Composable
fun BackupRestorePreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    var error: String? by rememberSaveable { mutableStateOf(null) }
    val backupLauncher = backupLauncher { error = it }
    val restoreLauncher = restoreLauncher { error = it }
    Preference(name = setting.title, onClick = { showDialog = true })
    if (showDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            content = { Text(stringResource(R.string.backup_restore_message)) },
            confirmButtonText = stringResource(R.string.button_backup),
            neutralButtonText = stringResource(R.string.button_restore),
            onNeutral = {
                showDialog = false
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                restoreLauncher.launch(intent)
            },
            onConfirmed = {
                val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(
                        Intent.EXTRA_TITLE,
                        ctx.getString(R.string.english_ime_name)
                            .replace(" ", "_") + "_backup_$currentDate.zip"
                    )
                    .setType("application/zip")
                backupLauncher.launch(intent)
            }
        )
    }
    if (error != null) {
        InfoDialog(
            if (error!!.startsWith("b"))
                stringResource(R.string.backup_error, error!!.drop(1))
            else stringResource(R.string.restore_error, error!!.drop(1))
        ) { error = null }
    }
}

@Composable
private fun backupLauncher(onError: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                val backupPayload = collectBackupFiles(ctx)
                val outputStream = requireNotNull(ctx.getActivity()?.contentResolver?.openOutputStream(uri)) {
                    "Unable to open backup destination"
                }
                outputStream.use { os ->
                    // write files to zip
                    val zipStream = ZipOutputStream(os)
                    backupPayload.files.forEach {
                        val fileStream = FileInputStream(it).buffered()
                        zipStream.putNextEntry(ZipEntry(it.path.replace(backupPayload.filesPrefix, "")))
                        fileStream.copyTo(zipStream, 8 * 1024)
                        fileStream.close()
                        zipStream.closeEntry()
                    }
                    backupPayload.protectedFiles.forEach {
                        val fileStream = FileInputStream(it).buffered()
                        zipStream.putNextEntry(ZipEntry(it.path.replace(backupPayload.protectedFilesPrefix, "unprotected/")))
                        fileStream.copyTo(zipStream, 8 * 1024)
                        fileStream.close()
                        zipStream.closeEntry()
                    }
                    zipStream.putNextEntry(ZipEntry(PREFS_FILE_NAME))
                    settingsToJsonStream(ctx.prefs().all, zipStream)
                    zipStream.closeEntry()
                    zipStream.putNextEntry(ZipEntry(PROTECTED_PREFS_FILE_NAME))
                    settingsToJsonStream(ctx.protectedPrefs().all, zipStream)
                    zipStream.closeEntry()
                    zipStream.close()
                }
            } catch (t: Throwable) {
                postToMain { onError("b" + (t.message ?: "Unknown error")) }
                Log.w("AdvancedScreen", "error during backup", t)
            }
        }
    }
}

@Composable
private fun restoreLauncher(onError: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            var listenerStopped = false
            var stagedBackup: StagedBackup? = null
            try {
                val restoreInputStream = requireNotNull(ctx.getActivity()?.contentResolver?.openInputStream(uri)) {
                    "Unable to open backup archive"
                }
                restoreInputStream.use { inputStream ->
                    stagedBackup = stageBackup(ctx, inputStream)
                }
                LayoutUtilsCustom.onLayoutFileChanged()
                Settings.getInstance().stopListener()
                listenerStopped = true
                stagedBackup?.let { applyStagedBackup(ctx, it) }
                postToMain {
                    Toast.makeText(ctx, ctx.getString(R.string.backup_restored), Toast.LENGTH_LONG).show()
                    refreshAfterRestore(ctx)
                }
            } catch (t: Throwable) {
                postToMain {
                    if (listenerStopped) {
                        Settings.getInstance().startListener()
                    }
                    onError("r" + (t.message ?: "Unknown error"))
                }
                Log.w("AdvancedScreen", "error during restore", t)
            } finally {
                stagedBackup?.root?.deleteRecursively()
            }
        }
    }
}

// Keys that must never be written to a backup archive. Provider API keys live in an
// encrypted shared-prefs bucket and is scrubbed from plain prefs by SecretStore, but we filter
// here as defense-in-depth against pre-migration or future pref leaks.
private val SENSITIVE_BACKUP_KEYS = setOf(
    Settings.PREF_OPENROUTER_API_KEY,
    Settings.PREF_PAYPERQ_API_KEY,
    LEGACY_PINNED_CLIPS_KEY,
)

@Suppress("UNCHECKED_CAST") // it is checked... but whatever (except string set, because can't check for that))
internal fun settingsToJsonString(settings: Map<String?, Any?>): String {
    val filtered = settings.filterKeys { it !in SENSITIVE_BACKUP_KEYS }
    val booleans = filtered.filter { it.key is String && it.value is Boolean } as Map<String, Boolean>
    val ints = filtered.filter { it.key is String && it.value is Int } as Map<String, Int>
    val longs = filtered.filter { it.key is String && it.value is Long } as Map<String, Long>
    val floats = filtered.filter { it.key is String && it.value is Float } as Map<String, Float>
    val strings = filtered.filter { it.key is String && it.value is String } as Map<String, String>
    val stringSets = filtered.filter { it.key is String && it.value is Set<*> } as Map<String, Set<String>>
    return buildString {
        append("boolean settings\n")
        append(Json.encodeToString(booleans))
        append("\nint settings\n")
        append(Json.encodeToString(ints))
        append("\nlong settings\n")
        append(Json.encodeToString(longs))
        append("\nfloat settings\n")
        append(Json.encodeToString(floats))
        append("\nstring settings\n")
        append(Json.encodeToString(strings))
        append("\nstring set settings\n")
        append(Json.encodeToString(stringSets))
    }
}

private fun settingsToJsonStream(settings: Map<String?, Any?>, out: OutputStream) {
    out.write(settingsToJsonString(settings).toByteArray())
}

internal fun parseSettingsSnapshot(list: List<String>): SettingsSnapshot {
    val i = list.iterator()
    try {
        val booleans = mutableMapOf<String, Boolean>()
        val ints = mutableMapOf<String, Int>()
        val longs = mutableMapOf<String, Long>()
        val floats = mutableMapOf<String, Float>()
        val strings = mutableMapOf<String, String>()
        val stringSets = mutableMapOf<String, Set<String>>()
        while (i.hasNext()) {
            when (i.next()) {
                "boolean settings" -> booleans.putAll(Json.decodeFromString<Map<String, Boolean>>(i.next()))
                "int settings" -> ints.putAll(Json.decodeFromString<Map<String, Int>>(i.next()))
                "long settings" -> longs.putAll(Json.decodeFromString<Map<String, Long>>(i.next()))
                "float settings" -> floats.putAll(Json.decodeFromString<Map<String, Float>>(i.next()))
                "string settings" -> strings.putAll(Json.decodeFromString<Map<String, String>>(i.next()))
                "string set settings" -> stringSets.putAll(Json.decodeFromString<Map<String, Set<String>>>(i.next()))
            }
        }
        return SettingsSnapshot(booleans, ints, longs, floats, strings, stringSets)
    } catch (e: Exception) {
        throw IllegalArgumentException("Malformed settings backup", e)
    }
}

internal data class SettingsSnapshot(
    val booleans: Map<String, Boolean> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
    val stringSets: Map<String, Set<String>> = emptyMap(),
)

private fun SharedPreferences.Editor.putAll(snapshot: SettingsSnapshot) {
    snapshot.booleans.forEach { putBoolean(it.key, it.value) }
    snapshot.ints.forEach { putInt(it.key, it.value) }
    snapshot.longs.forEach { putLong(it.key, it.value) }
    snapshot.floats.forEach { putFloat(it.key, it.value) }
    snapshot.strings.forEach { putString(it.key, it.value) }
    snapshot.stringSets.forEach { putStringSet(it.key, it.value) }
}

private data class BackupFiles(
    val files: List<File>,
    val protectedFiles: List<File>,
    val filesPrefix: String,
    val protectedFilesPrefix: String,
)

private data class StagedBackup(
    val root: File,
    val filesDir: File,
    val protectedFilesDir: File,
    val prefs: SettingsSnapshot?,
    val protectedPrefs: SettingsSnapshot?,
)

private fun collectBackupFiles(ctx: android.content.Context): BackupFiles {
    val filesDir = ctx.filesDir ?: error("Files directory unavailable")
    val filesPrefix = filesDir.path + File.separator
    val files = filesDir.walk().filter { file ->
        val path = file.path.removePrefix(filesPrefix)
        file.isFile && isAllowedBackupFile(path)
    }.toList()

    val protectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
    val protectedFilesPrefix = protectedFilesDir.path + File.separator
    val protectedFiles = protectedFilesDir.walk().filter { file ->
        val path = file.path.removePrefix(protectedFilesPrefix)
        file.isFile && isAllowedBackupFile(path)
    }.toList()
    return BackupFiles(files, protectedFiles, filesPrefix, protectedFilesPrefix)
}

private fun stageBackup(ctx: android.content.Context, inputStream: InputStream): StagedBackup {
    val stageRoot = File(ctx.cacheDir, "backup_restore_stage_${System.currentTimeMillis()}")
    val stageFilesDir = File(stageRoot, "files")
    val stageProtectedFilesDir = File(stageRoot, "device_protected")
    var prefsSnapshot: SettingsSnapshot? = null
    var protectedPrefsSnapshot: SettingsSnapshot? = null
    var restoredFileBytes = 0L
    ZipInputStream(inputStream).use { zip ->
        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            val entryName = normalizeBackupEntryName(entry.name)
            when {
                entry.isDirectory -> Unit
                entryName.startsWith("unprotected/") -> {
                    val adjustedName = entryName.removePrefix("unprotected/")
                    if (isAllowedBackupFile(adjustedName)) {
                        restoredFileBytes += copyRestoreEntryToNewFile(zip, File(stageProtectedFilesDir, adjustedName))
                        require(restoredFileBytes <= MAX_RESTORE_TOTAL_BYTES) { "Backup file entries exceed total size limit" }
                    }
                }
                isAllowedBackupFile(entryName) -> {
                    restoredFileBytes += copyRestoreEntryToNewFile(zip, File(stageFilesDir, entryName))
                    require(restoredFileBytes <= MAX_RESTORE_TOTAL_BYTES) { "Backup file entries exceed total size limit" }
                }
                entryName == PREFS_FILE_NAME -> {
                    prefsSnapshot = parseSettingsSnapshot(readSnapshotCapped(zip).split("\n"))
                }
                entryName == PROTECTED_PREFS_FILE_NAME -> {
                    protectedPrefsSnapshot = parseSettingsSnapshot(readSnapshotCapped(zip).split("\n"))
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return StagedBackup(
        root = stageRoot,
        filesDir = stageFilesDir,
        protectedFilesDir = stageProtectedFilesDir,
        prefs = prefsSnapshot,
        protectedPrefs = protectedPrefsSnapshot,
    )
}

private fun applyStagedBackup(ctx: android.content.Context, backup: StagedBackup) {
    val filesDir = ctx.filesDir ?: error("Files directory unavailable")
    val protectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
    val rollbackRoot = File(ctx.cacheDir, "backup_restore_rollback_${System.currentTimeMillis()}")
    val rollbackFilesDir = File(rollbackRoot, "files")
    val rollbackProtectedFilesDir = File(rollbackRoot, "device_protected")
    filesDir.mkdirs()
    protectedFilesDir.mkdirs()
    try {
        snapshotAllowedFiles(filesDir, rollbackFilesDir)
        snapshotAllowedFiles(protectedFilesDir, rollbackProtectedFilesDir)
        copyDirectoryContents(backup.filesDir, filesDir)
        copyDirectoryContents(backup.protectedFilesDir, protectedFilesDir)
        backup.prefs?.let { snapshot ->
            ctx.prefs().edit {
                clear()
                putAll(snapshot)
            }
        }
        backup.protectedPrefs?.let { snapshot ->
            ctx.protectedPrefs().edit {
                clear()
                putAll(snapshot)
            }
        }
    } catch (t: Throwable) {
        restoreAllowedFiles(rollbackFilesDir, filesDir)
        restoreAllowedFiles(rollbackProtectedFilesDir, protectedFilesDir)
        throw t
    } finally {
        rollbackRoot.deleteRecursively()
    }
}

private fun snapshotAllowedFiles(source: File, target: File) {
    if (!source.exists()) return
    source.walkTopDown()
        .filter { it.isFile && isAllowedBackupFile(it.relativeTo(source).path) }
        .forEach { file ->
            file.inputStream().buffered().use { input ->
                FileUtils.copyStreamToNewFile(input, File(target, file.relativeTo(source).path))
            }
        }
}

private fun restoreAllowedFiles(snapshot: File, target: File) {
    deleteAllowedBackupFiles(target)
    copyDirectoryContents(snapshot, target)
}

private fun deleteAllowedBackupFiles(root: File) {
    if (!root.exists()) return
    root.walkBottomUp()
        .filter { it.isFile && isAllowedBackupFile(it.relativeTo(root).path) }
        .forEach { it.delete() }
}

private fun copyDirectoryContents(source: File, target: File) {
    if (!source.exists()) return
    source.walkTopDown().forEach { file ->
        if (file == source) return@forEach
        val relativePath = file.relativeTo(source).path
        val targetFile = File(target, relativePath)
        if (file.isDirectory) {
            if (targetFile.isFile) {
                targetFile.delete()
            }
            targetFile.mkdirs()
        } else {
            if (targetFile.isDirectory) {
                targetFile.deleteRecursively()
            }
            file.inputStream().buffered().use { input ->
                FileUtils.copyStreamToNewFile(input, targetFile)
            }
        }
    }
}

internal fun normalizeBackupEntryName(name: String): String {
    val normalized = name.replace('\\', '/').trimStart('/')
    require(normalized.split('/').none { it == ".." }) { "Unsafe backup entry: $name" }
    return normalized
}

internal fun copyRestoreEntryToNewFile(input: InputStream, target: File): Long {
    val parent = target.parentFile ?: throw IllegalArgumentException("Restore target has no parent")
    if (!parent.exists() && !parent.mkdirs()) {
        throw IllegalArgumentException("Could not create restore target folder")
    }
    try {
        java.io.FileOutputStream(target).use { output ->
            return copyRestoreEntry(input, output)
        }
    } catch (t: Throwable) {
        target.delete()
        throw t
    }
}

private fun copyRestoreEntry(input: InputStream, output: OutputStream): Long {
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        total += read
        if (total > MAX_RESTORE_ENTRY_BYTES) {
            throw IllegalArgumentException("Backup file entry exceeds size limit")
        }
        output.write(buffer, 0, read)
    }
    output.flush()
    return total
}

// Real preference snapshots are kilobytes; this cap is ~3 orders of magnitude of headroom and
// guards against a malformed archive that would otherwise OOM on the IO thread.
private const val MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024
private const val MAX_RESTORE_ENTRY_BYTES = 32L * 1024L * 1024L
private const val MAX_RESTORE_TOTAL_BYTES = 64L * 1024L * 1024L

private fun readSnapshotCapped(input: InputStream): String {
    val buf = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val n = input.read(chunk)
        if (n == -1) break
        total += n
        if (total > MAX_SNAPSHOT_BYTES) {
            throw IllegalArgumentException("Settings snapshot exceeds size limit")
        }
        buf.write(chunk, 0, n)
    }
    return buf.toString(Charsets.UTF_8.name())
}

private fun refreshAfterRestore(ctx: android.content.Context) {
    checkVersionUpgrade(ctx)
    transferOldPinnedClips(ctx)
    Settings.getInstance().startListener()
    SubtypeSettings.reloadEnabledSubtypes(ctx)
    val newDictBroadcast = Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
        .setPackage(ctx.packageName)
    ctx.getActivity()?.sendBroadcast(newDictBroadcast)
    LayoutUtilsCustom.onLayoutFileChanged()
    LayoutUtilsCustom.removeMissingLayouts(ctx)
    (ctx.getActivity() as? SettingsActivity)?.prefChanged()
    SupportedEmojis.load(ctx)
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}

private fun postToMain(action: () -> Unit) {
    Handler(Looper.getMainLooper()).post(action)
}

private const val PREFS_FILE_NAME = "preferences.json"
private const val PROTECTED_PREFS_FILE_NAME = "protected_preferences.json"
private const val LEGACY_PINNED_CLIPS_KEY = "pinned_clips"

internal val backupFilePatterns by lazy { listOf(
    "blacklists${File.separator}.*\\.txt".toRegex(),
    "layouts${File.separator}.*${LayoutUtilsCustom.CUSTOM_LAYOUT_PREFIX}+\\..{0,4}".toRegex(), // can't expect a period at the end, as this would break restoring older backups
    "custom_background_image.*".toRegex(),
    "custom_font".toRegex(),
    "custom_emoji_font".toRegex(),
) }

internal fun isAllowedBackupFile(path: String) = backupFilePatterns.any { path.matches(it) }
