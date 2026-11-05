package lt.stillhome.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * All settings live in one flat key -> string map (DataStore Preferences).
 * A flat map makes the 24 h rule simple: every change is "key X: old -> new" and
 * [lt.stillhome.core.Rules.isLoosening] classifies it per key.
 *
 * The map is mirrored in memory so the accessibility service can read it synchronously
 * on every event without touching disk.
 */
object Store {
    private lateinit var ds: DataStore<Preferences>
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _map = MutableStateFlow<Map<String, String>>(emptyMap())
    val map: StateFlow<Map<String, String>> = _map

    fun init(ctx: Context) {
        if (::ds.isInitialized) return
        ds = ctx.applicationContext.settingsStore
        // One small blocking read at process start (a few KB) so every caller sees real values.
        _map.value = runBlocking { ds.data.first() }.asMap().map { it.key.name to it.value.toString() }.toMap()
    }

    fun get(key: String): String? = _map.value[key]
    fun str(key: String, def: String) = _map.value[key] ?: def
    fun int(key: String, def: Int) = _map.value[key]?.toIntOrNull() ?: def
    fun long(key: String, def: Long) = _map.value[key]?.toLongOrNull() ?: def
    fun bool(key: String, def: Boolean) = _map.value[key]?.let { it == "1" || it == "true" } ?: def
    fun list(key: String): List<String> = _map.value[key]?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
    fun keys(prefix: String) = _map.value.keys.filter { it.startsWith(prefix) }

    /** Raw write, no 24 h rule. Use [lt.stillhome.core.Rules.change] for anything that is a rule. */
    fun set(key: String, value: String?) = setAll(mapOf(key to value))

    fun setAll(changes: Map<String, String?>) {
        if (changes.isEmpty()) return
        // Atomic read-modify-write: the a11y service, GuardService ticks and receivers write from other threads.
        _map.update { cur -> cur.toMutableMap().also { m -> changes.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v } } }
        io.launch {
            ds.edit { p ->
                changes.forEach { (k, v) ->
                    val key = stringPreferencesKey(k)
                    if (v == null) p.remove(key) else p[key] = v
                }
            }
        }
    }

    /** Like [set], but returns only once it's on disk (broadcast receivers: the process may die right after). */
    fun setDurable(key: String, value: String?) {
        set(key, value)
        runBlocking { ds.edit { p -> stringPreferencesKey(key).let { k -> if (value == null) p.remove(k) else p[k] = value } } }
    }

    fun setList(key: String, values: List<String>) = set(key, values.joinToString("\n"))
}
