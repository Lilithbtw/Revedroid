package app.revedroid.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.revedroid.data.api.CloudreveJson
import app.revedroid.data.api.Token
import app.revedroid.data.api.User
import app.revedroid.data.repo.ShareExpiry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "revedroid")

@Serializable
data class StoredSession(
    val serverUrl: String,
    val user: User,
    val token: Token,
) {
    /** Identifies the account: the same user on the same server is the same account. */
    val key: String get() = "${user.id}@$serverUrl"
}

enum class ThemeMode { System, Light, Dark }

enum class FileViewMode { List, Grid }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val viewMode: FileViewMode = FileViewMode.List,
    val shareExpiry: ShareExpiry = ShareExpiry.Never,
)

class AppStore(
    context: Context,
    private val secretBox: SecretBox = SecretBox(),
) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<AppSettings> =
        store.data.map { prefs ->
            AppSettings(
                themeMode = prefs[THEME].toEnum(ThemeMode.System),
                dynamicColor = prefs[DYNAMIC_COLOR] ?: true,
                viewMode = prefs[VIEW_MODE].toEnum(FileViewMode.List),
                shareExpiry = prefs[SHARE_EXPIRY].toEnum(ShareExpiry.Never),
            )
        }

    suspend fun serverUrl(): String? = store.data.first()[SERVER_URL]

    suspend fun setServerUrl(url: String) {
        store.edit { it[SERVER_URL] = url }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[THEME] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[DYNAMIC_COLOR] = enabled }
    }

    suspend fun setViewMode(mode: FileViewMode) {
        store.edit { it[VIEW_MODE] = mode.name }
    }

    suspend fun readSession(): StoredSession? {
        val sealed = store.data.first()[SESSION] ?: return null
        val plain = secretBox.decrypt(sealed) ?: return null
        return runCatching { CloudreveJson.decodeFromString<StoredSession>(plain) }.getOrNull()
    }

    suspend fun writeSession(session: StoredSession?) {
        store.edit { prefs ->
            if (session == null) {
                prefs.remove(SESSION)
            } else {
                prefs[SESSION] = secretBox.encrypt(CloudreveJson.encodeToString(StoredSession.serializer(), session))
            }
        }
    }

    suspend fun setShareExpiry(expiry: ShareExpiry) {
        store.edit { it[SHARE_EXPIRY] = expiry.name }
    }

    suspend fun lastUploadFolder(): String? = store.data.first()[LAST_UPLOAD_FOLDER]

    suspend fun setLastUploadFolder(uri: String) {
        store.edit { it[LAST_UPLOAD_FOLDER] = uri }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val SESSION = stringPreferencesKey("session")
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val SHARE_EXPIRY = stringPreferencesKey("share_expiry")
        val LAST_UPLOAD_FOLDER = stringPreferencesKey("last_upload_folder")

        inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
            this?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
    }
}
