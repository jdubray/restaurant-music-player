package app.musicplayer.restaurant.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class Settings(private val context: Context) {

    val serverUrl: Flow<String> = context.dataStore.data.map { it[K.SERVER_URL] ?: DEFAULT_URL }
    val hoursJson: Flow<String?> = context.dataStore.data.map { it[K.HOURS_JSON] }
    val userOverride: Flow<UserOverride> = context.dataStore.data.map {
        runCatching { UserOverride.valueOf(it[K.USER_OVERRIDE] ?: "NONE") }.getOrDefault(UserOverride.NONE)
    }
    val userOverrideUntilMs: Flow<Long> = context.dataStore.data.map { it[K.USER_OVERRIDE_UNTIL] ?: 0L }
    val lastSyncAtMs: Flow<Long> = context.dataStore.data.map { it[K.LAST_SYNC_AT] ?: 0L }
    val lastSyncOk: Flow<Boolean> = context.dataStore.data.map { it[K.LAST_SYNC_OK] ?: false }
    val lastSyncMessage: Flow<String> = context.dataStore.data.map { it[K.LAST_SYNC_MSG] ?: "" }

    suspend fun setServerUrl(url: String) = context.dataStore.edit { it[K.SERVER_URL] = url.trim().trimEnd('/') }

    suspend fun setHoursJson(json: String) = context.dataStore.edit { it[K.HOURS_JSON] = json }

    suspend fun setUserOverride(override: UserOverride, untilMs: Long) = context.dataStore.edit {
        it[K.USER_OVERRIDE] = override.name
        it[K.USER_OVERRIDE_UNTIL] = untilMs
    }

    suspend fun recordSyncOk(message: String) = context.dataStore.edit {
        it[K.LAST_SYNC_AT] = System.currentTimeMillis()
        it[K.LAST_SYNC_OK] = true
        it[K.LAST_SYNC_MSG] = message
    }

    suspend fun recordSyncFail(message: String) = context.dataStore.edit {
        it[K.LAST_SYNC_AT] = System.currentTimeMillis()
        it[K.LAST_SYNC_OK] = false
        it[K.LAST_SYNC_MSG] = message
    }

    private object K {
        val SERVER_URL = stringPreferencesKey("server_url")
        val HOURS_JSON = stringPreferencesKey("hours_json")
        val USER_OVERRIDE = stringPreferencesKey("user_override")
        val USER_OVERRIDE_UNTIL = longPreferencesKey("user_override_until")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val LAST_SYNC_OK = booleanPreferencesKey("last_sync_ok")
        val LAST_SYNC_MSG = stringPreferencesKey("last_sync_msg")
    }

    companion object {
        const val DEFAULT_URL = "http://192.168.1.248:8080"
    }
}
