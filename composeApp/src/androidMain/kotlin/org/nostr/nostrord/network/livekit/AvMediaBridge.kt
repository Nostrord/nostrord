package org.nostr.nostrord.network.livekit

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android plumbing the AV space needs and `commonMain` cannot reach: the application context
 * LiveKit is created with, and the runtime mic/camera grant.
 *
 * Mirrors the NIP-55 bridge: the Application installs the context, the Activity installs the
 * launcher, and suspending callers await the user's answer.
 */
object AvMediaBridge {
    @Volatile
    var appContext: Context? = null
        private set

    private var launcher: ActivityResultLauncher<String>? = null

    @Volatile private var pending: CompletableDeferred<Boolean>? = null
    private val mutex = Mutex()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun register(activity: ComponentActivity) {
        launcher =
            activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                pending?.also { pending = null }?.complete(granted)
            }
    }

    fun isGranted(permission: String): Boolean {
        val context = appContext ?: return false
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Grant state for [permission], prompting once if the user has not answered yet. */
    suspend fun ensurePermission(permission: String): Boolean = mutex.withLock {
        if (isGranted(permission)) return true
        val active = launcher ?: return false
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        try {
            withContext(Dispatchers.Main) { active.launch(permission) }
            deferred.await()
        } finally {
            pending = null
        }
    }
}
