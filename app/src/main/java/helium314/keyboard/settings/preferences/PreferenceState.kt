// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.edit
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.latin.utils.prefs
import java.util.WeakHashMap

@Composable
fun rememberBooleanPreferenceState(key: String, default: Boolean): MutableState<Boolean> {
    return rememberPreferenceState(key) { prefs -> prefs.safeGet(key, default) { getBoolean(key, default) } }
}

@Composable
fun rememberStringPreferenceState(key: String, default: String): MutableState<String> {
    return rememberPreferenceState(key) { prefs -> prefs.safeGet(key, default) { getString(key, default) ?: default } }
}

@Composable
fun rememberIntPreferenceState(key: String, default: Int): MutableState<Int> {
    return rememberPreferenceState(key) { prefs -> prefs.safeGet(key, default) { getInt(key, default) } }
}

@Composable
fun rememberLongPreferenceState(key: String, default: Long): MutableState<Long> {
    return rememberPreferenceState(key) { prefs -> prefs.safeGet(key, default) { getLong(key, default) } }
}

@Composable
fun rememberFloatPreferenceState(key: String, default: Float): MutableState<Float> {
    return rememberPreferenceState(key) { prefs -> prefs.safeGet(key, default) { getFloat(key, default) } }
}

/**
 * SharedPreferences throws ClassCastException when the stored type differs from the requested
 * one (e.g. after a schema change). Returning the default keeps the UI alive; the next write
 * from the user corrects the on-disk type.
 */
private inline fun <T> SharedPreferences.safeGet(key: String, default: T, block: SharedPreferences.() -> T): T =
    try { block() } catch (_: ClassCastException) {
        edit { remove(key) }
        default
    }

@Composable
private fun <T> rememberPreferenceState(
    key: String,
    readValue: (SharedPreferences) -> T
): MutableState<T> {
    val prefs = LocalContext.current.prefs()
    val state = remember(prefs, key) {
        mutableStateOf(readValue(prefs))
    }

    DisposableEffect(prefs, key) {
        val unsubscribe = PreferenceWatchers.subscribe(prefs, key) {
            state.value = readValue(prefs)
        }
        onDispose(unsubscribe)
    }

    return state
}

/**
 * One [SharedPreferences.OnSharedPreferenceChangeListener] per preferences file, shared by every
 * row on screen.
 *
 * Each row used to register its own listener, so a screen showing 20 rows attached 20 platform
 * listeners, and a single write walked all of them just for each to compare its key and discard the
 * callback. Here a write is one hash lookup, and only the rows watching that key wake up.
 *
 * All access happens on the main thread — composition effects and preference callbacks both run
 * there — but the maps are guarded anyway, since a background write is what triggers the dispatch.
 */
internal object PreferenceWatchers {
    private class Watcher(private val prefs: SharedPreferences) {
        val byKey = HashMap<String, MutableList<() -> Unit>>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            // Snapshotted under the lock before dispatch: a callback may add or remove rows, and
            // mutating the list mid-iteration would throw.
            val callbacks = synchronized(PreferenceWatchers) { changedKey?.let { byKey[it]?.toList() } }
            callbacks?.forEach { it() }
        }

        fun register() = prefs.registerOnSharedPreferenceChangeListener(listener)
        fun unregister() = prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    // Weak keys: an activity-scoped SharedPreferences instance must not be pinned by this cache.
    private val watchers = WeakHashMap<SharedPreferences, Watcher>()

    fun subscribe(prefs: SharedPreferences, key: String, onChange: () -> Unit): () -> Unit {
        synchronized(this) {
            val watcher = watchers.getOrPut(prefs) { Watcher(prefs).also { it.register() } }
            watcher.byKey.getOrPut(key) { mutableListOf() }.add(onChange)
        }
        return { unsubscribe(prefs, key, onChange) }
    }

    private fun unsubscribe(prefs: SharedPreferences, key: String, onChange: () -> Unit) = synchronized(this) {
        val watcher = watchers[prefs] ?: return@synchronized
        val callbacks = watcher.byKey[key] ?: return@synchronized
        // Identity removal: two rows watching the same key hold distinct but equal-looking lambdas.
        val index = callbacks.indexOfFirst { it === onChange }
        if (index < 0) return@synchronized
        callbacks.removeAt(index)
        if (callbacks.isEmpty()) watcher.byKey.remove(key)
        if (watcher.byKey.isEmpty()) {
            watcher.unregister()
            watchers.remove(prefs)
        }
    }
}
