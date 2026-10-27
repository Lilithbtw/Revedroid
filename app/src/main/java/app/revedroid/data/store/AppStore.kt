package app.revedroid.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
import java.time.Duration

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

/** Every signed-in account, and the one the app is currently using. */
@Serializable
data class StoredAccounts(
    val accounts: List<StoredSession> = emptyList(),
    val active: String? = null,
)

enum class ThemeMode { System, Light, Dark }

enum class FileViewMode { List, Grid }

/** How long the app may stay in the background before the passcode is asked for again. */
enum class LockTimeout(
    val delay: Duration,
) {
    Immediately(Duration.ZERO),
    OneMinute(Duration.ofMinutes(1)),
    FiveMinutes(Duration.ofMinutes(5)),
    ThirtyMinutes(Duration.ofMinutes(30)),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val viewMode: FileViewMode = FileViewMode.List,
    val appLock: Boolean = false,
    val passcodeLength: Int = 0,
    val biometricUnlock: Boolean = false,
    val lockTimeout: LockTimeout = LockTimeout.Immediately,
    val hideContent: Boolean = false,
    val shareExpiry: ShareExpiry = ShareExpiry.Never,
    val checkUpdates: Boolean = true,
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
                appLock = prefs[PASSCODE] != null,
                passcodeLength = prefs[PASSCODE_LENGTH] ?: 0,
                biometricUnlock = prefs[BIOMETRIC_SECRET] != null,
                lockTimeout = prefs[LOCK_TIMEOUT].toEnum(LockTimeout.Immediately),
                hideContent = prefs[HIDE_CONTENT] ?: false,
                shareExpiry = prefs[SHARE_EXPIRY].toEnum(ShareExpiry.Never),
                checkUpdates = prefs[CHECK_UPDATES] ?: true,
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

    suspend fun passcodeHash(): String? = store.data.first()[PASSCODE]

    /** Stores the hash from [Passcode.hash], or turns the app lock off when null. */
    suspend fun setPasscode(
        hash: String?,
        length: Int = 0,
    ) {
        store.edit { prefs ->
            if (hash == null) {
                prefs.remove(PASSCODE)
                prefs.remove(PASSCODE_LENGTH)
                prefs.remove(BIOMETRIC_SECRET)
            } else {
                prefs[PASSCODE] = hash
                prefs[PASSCODE_LENGTH] = length
            }
        }
    }

    suspend fun biometricSecret(): String? = store.data.first()[BIOMETRIC_SECRET]

    /** Stores the secret sealed by [BiometricKey], or turns biometric unlock off when null. */
    suspend fun setBiometricSecret(sealed: String?) {
        store.edit { prefs ->
            if (sealed == null) prefs.remove(BIOMETRIC_SECRET) else prefs[BIOMETRIC_SECRET] = sealed
        }
    }

    suspend fun setLockTimeout(timeout: LockTimeout) {
        store.edit { it[LOCK_TIMEOUT] = timeout.name }
    }

    suspend fun setHideContent(enabled: Boolean) {
        store.edit { it[HIDE_CONTENT] = enabled }
    }

    suspend fun setShareExpiry(expiry: ShareExpiry) {
        store.edit { it[SHARE_EXPIRY] = expiry.name }
    }

    suspend fun setCheckUpdates(enabled: Boolean) {
        store.edit { it[CHECK_UPDATES] = enabled }
    }

    suspend fun lastUpdateCheck(): Long = store.data.first()[LAST_UPDATE_CHECK] ?: 0L

    suspend fun setLastUpdateCheck(epochMillis: Long) {
        store.edit { it[LAST_UPDATE_CHECK] = epochMillis }
    }

    suspend fun lastUploadFolder(): String? = store.data.first()[LAST_UPLOAD_FOLDER]

    suspend fun setLastUploadFolder(uri: String) {
        store.edit { it[LAST_UPLOAD_FOLDER] = uri }
    }

    suspend fun readAccounts(): StoredAccounts {
        val prefs = store.data.first()
        prefs[ACCOUNTS]
            ?.let(secretBox::decrypt)
            ?.let { runCatching { CloudreveJson.decodeFromString<StoredAccounts>(it) }.getOrNull() }
            ?.let { return it }
        // Versions before multi-account support kept a single session; it becomes
        // the only account, so updating doesn't sign anyone out.
        val legacy =
            prefs[SESSION]
                ?.let(secretBox::decrypt)
                ?.let { runCatching { CloudreveJson.decodeFromString<StoredSession>(it) }.getOrNull() }
                ?: return StoredAccounts()
        return StoredAccounts(listOf(legacy), legacy.key)
    }

    suspend fun writeAccounts(accounts: StoredAccounts) {
        val sealed = secretBox.encrypt(CloudreveJson.encodeToString(StoredAccounts.serializer(), accounts))
        store.edit { prefs ->
            prefs[ACCOUNTS] = sealed
            prefs.remove(SESSION)
        }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val SESSION = stringPreferencesKey("session")
        val ACCOUNTS = stringPreferencesKey("accounts")
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val PASSCODE = stringPreferencesKey("passcode")
        val PASSCODE_LENGTH = intPreferencesKey("passcode_length")
        val BIOMETRIC_SECRET = stringPreferencesKey("biometric_secret")
        val LOCK_TIMEOUT = stringPreferencesKey("lock_timeout")
        val HIDE_CONTENT = booleanPreferencesKey("hide_content")
        val SHARE_EXPIRY = stringPreferencesKey("share_expiry")
        val CHECK_UPDATES = booleanPreferencesKey("check_updates")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val LAST_UPLOAD_FOLDER = stringPreferencesKey("last_upload_folder")

        inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
            this?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
    }
}
