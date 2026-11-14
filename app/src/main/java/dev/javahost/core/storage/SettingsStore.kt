package dev.javahost.core.storage

import android.content.Context
import androidx.core.content.edit
import dev.javahost.core.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * SharedPreferences-backed settings with a JSON payload. Preferences are a good
 * fit here: the payload is tiny, synchronous reads are fine, and we avoid adding
 * DataStore just for a dozen primitives.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("javahost_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun current(): AppSettings = _state.value

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        synchronized(lock) {
            val next = transform(_state.value)
            _state.value = next
            prefs.edit { putString(KEY, json.encodeToString(AppSettings.serializer(), next)) }
            return next
        }
    }

    private fun read(): AppSettings {
        val raw = prefs.getString(KEY, null) ?: return AppSettings()
        return runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
            .getOrDefault(AppSettings())
    }

    companion object {
        private const val KEY = "settings_json"
    }
}
