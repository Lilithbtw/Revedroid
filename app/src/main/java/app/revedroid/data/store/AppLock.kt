package app.revedroid.data.store

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UnlockResult {
    data object Unlocked : UnlockResult

    data object Wrong : UnlockResult

    /** Too many wrong attempts; try again at [retryAt] (elapsed realtime). */
    data class Wait(
        val retryAt: Long,
    ) : UnlockResult
}

/**
 * Decides when the passcode screen covers the app: at start-up when a passcode
 * is set, and when the app comes back from the background after the chosen
 * [LockTimeout]. Activities report in through [Application.ActivityLifecycleCallbacks];
 * a configuration change doesn't count as leaving the app.
 */
class AppLock(
    private val store: AppStore,
    scope: CoroutineScope,
) : Application.ActivityLifecycleCallbacks {
    private val _locked = MutableStateFlow<Boolean?>(null)

    /** Null until the settings have been read, then whether the passcode screen should show. */
    val locked: StateFlow<Boolean?> = _locked.asStateFlow()

    @Volatile
    private var settings = AppSettings()

    // Touched only on the main thread, from the lifecycle callbacks.
    private var started = 0
    private var backgroundSince = 0L
    private var externalSince = 0L

    private var failures = 0
    private var retryAt = 0L

    init {
        scope.launch {
            store.settings.collect { s ->
                settings = s
                // Setting a passcode shouldn't lock the user out on the spot.
                if (_locked.value == null || !s.appLock) _locked.value = s.appLock
            }
        }
    }

    /**
     * Call before opening another app's screen (a file picker, the share sheet)
     * so coming back from it within a few minutes doesn't ask for the passcode.
     */
    fun expectExternalActivity() {
        externalSince = SystemClock.elapsedRealtime()
    }

    suspend fun unlock(passcode: String): UnlockResult {
        val now = SystemClock.elapsedRealtime()
        if (now < retryAt) return UnlockResult.Wait(retryAt)
        val stored = store.passcodeHash()
        val ok = stored == null || withContext(Dispatchers.Default) { Passcode.verify(passcode, stored) }
        if (ok) {
            failures = 0
            _locked.value = false
            return UnlockResult.Unlocked
        }
        failures++
        if (failures % MAX_ATTEMPTS == 0) {
            retryAt = now + LOCKOUT_MILLIS * (failures / MAX_ATTEMPTS)
            return UnlockResult.Wait(retryAt)
        }
        return UnlockResult.Wrong
    }

    fun unlockWithBiometrics() {
        failures = 0
        _locked.value = false
    }

    override fun onActivityStarted(activity: Activity) {
        if (started++ > 0 || backgroundSince == 0L) return
        val now = SystemClock.elapsedRealtime()
        val away = now - backgroundSince
        val external = externalSince != 0L && now - externalSince < EXTERNAL_GRACE_MILLIS
        backgroundSince = 0L
        externalSince = 0L
        if (settings.appLock && !external && away >= settings.lockTimeout.delay.toMillis()) _locked.value = true
    }

    override fun onActivityStopped(activity: Activity) {
        if (--started == 0 && !activity.isChangingConfigurations) backgroundSince = SystemClock.elapsedRealtime()
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MILLIS = 30_000L
        const val EXTERNAL_GRACE_MILLIS = 5 * 60_000L
    }
}
