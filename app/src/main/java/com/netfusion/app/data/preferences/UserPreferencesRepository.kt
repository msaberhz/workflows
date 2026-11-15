package com.netfusion.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.netfusion.app.domain.model.AppLanguage
import com.netfusion.app.domain.model.ConnectionMode
import com.netfusion.app.domain.model.ServerConfig
import com.netfusion.app.domain.model.ThemePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "netfusion_prefs")

data class UserSettings(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val theme: ThemePreference = ThemePreference.DARK,
    val preferredMode: ConnectionMode = ConnectionMode.SMART,
    val autoConnectOnBoot: Boolean = false,
    val autoConnectOnLaunch: Boolean = false,
    val cellularDataLimitMb: Long = 0L, // 0 = unlimited
    val warnBeforeCellularBonding: Boolean = true,
    val customDns: String = "1.1.1.1",
    val mtuSize: Int = 1360,
    val selectedServerIndex: Int = 0,
    val serverHost: String = "",
    val serverPort: Int = 51880,
    val serverToken: String = ""
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val THEME = stringPreferencesKey("theme")
        val PREFERRED_MODE = stringPreferencesKey("preferred_mode")
        val AUTO_CONNECT_BOOT = booleanPreferencesKey("auto_connect_boot")
        val AUTO_CONNECT_LAUNCH = booleanPreferencesKey("auto_connect_launch")
        val CELLULAR_LIMIT_MB = longPreferencesKey("cellular_limit_mb")
        val WARN_CELLULAR = booleanPreferencesKey("warn_cellular")
        val CUSTOM_DNS = stringPreferencesKey("custom_dns")
        val MTU_SIZE = intPreferencesKey("mtu_size")
        val SERVER_INDEX = intPreferencesKey("server_index")
        val SERVER_HOST = stringPreferencesKey("server_host")
        val SERVER_PORT = intPreferencesKey("server_port")
        val SERVER_TOKEN = stringPreferencesKey("server_token")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            language = prefs[Keys.LANGUAGE]?.let { tag ->
                AppLanguage.entries.firstOrNull { it.tag == tag }
            } ?: AppLanguage.ENGLISH,
            theme = prefs[Keys.THEME]?.let { name ->
                runCatching { ThemePreference.valueOf(name) }.getOrNull()
            } ?: ThemePreference.DARK,
            preferredMode = prefs[Keys.PREFERRED_MODE]?.let { name ->
                runCatching { ConnectionMode.valueOf(name) }.getOrNull()
            } ?: ConnectionMode.SMART,
            autoConnectOnBoot = prefs[Keys.AUTO_CONNECT_BOOT] ?: false,
            autoConnectOnLaunch = prefs[Keys.AUTO_CONNECT_LAUNCH] ?: false,
            cellularDataLimitMb = prefs[Keys.CELLULAR_LIMIT_MB] ?: 0L,
            warnBeforeCellularBonding = prefs[Keys.WARN_CELLULAR] ?: true,
            customDns = prefs[Keys.CUSTOM_DNS] ?: "1.1.1.1",
            mtuSize = prefs[Keys.MTU_SIZE] ?: 1360,
            selectedServerIndex = prefs[Keys.SERVER_INDEX] ?: 0,
            serverHost = prefs[Keys.SERVER_HOST] ?: "",
            serverPort = prefs[Keys.SERVER_PORT] ?: 51880,
            serverToken = prefs[Keys.SERVER_TOKEN] ?: ""
        )
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { it[Keys.LANGUAGE] = language.tag }
    }

    suspend fun setTheme(theme: ThemePreference) {
        context.dataStore.edit { it[Keys.THEME] = theme.name }
    }

    suspend fun setPreferredMode(mode: ConnectionMode) {
        context.dataStore.edit { it[Keys.PREFERRED_MODE] = mode.name }
    }

    suspend fun setAutoConnectOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_CONNECT_BOOT] = enabled }
    }

    suspend fun setCellularLimitMb(limitMb: Long) {
        context.dataStore.edit { it[Keys.CELLULAR_LIMIT_MB] = limitMb }
    }

    suspend fun setCustomDns(dns: String) {
        context.dataStore.edit { it[Keys.CUSTOM_DNS] = dns }
    }

    suspend fun setServerConfig(host: String, port: Int, token: String, index: Int = 0) {
        context.dataStore.edit {
            it[Keys.SERVER_HOST] = host.trim()
            it[Keys.SERVER_PORT] = port
            it[Keys.SERVER_TOKEN] = token.trim()
            it[Keys.SERVER_INDEX] = index
        }
    }

    fun buildActiveServerConfig(settings: UserSettings): ServerConfig {
        return ServerConfig(
            name = if (settings.serverHost.isBlank()) "Unconfigured Gateway" else settings.serverHost,
            host = settings.serverHost,
            port = settings.serverPort,
            authToken = settings.serverToken,
            region = "Custom VPS"
        )
    }
}
