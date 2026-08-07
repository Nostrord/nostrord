package org.nostr.nostrord.auth.pomegranate

import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import java.lang.ref.WeakReference

/**
 * Android sign-in without a browser: the system account picker (Credential Manager over Play
 * services) returns a Google ID token addressed to the central's web client, which the central
 * verifies at `POST /login/google/android`. Falls back to null on anything the browser flow can
 * still handle: no Play services, no Google account on the device, or an app build whose package
 * and signing fingerprint are not registered as an Android OAuth client in the central's Google
 * Cloud project (Play services refuses the token in that case).
 */
internal actual object PomegranateNativeGoogle {
    actual val isAvailable: Boolean get() = PomegranateGoogleSignIn.activity != null

    actual suspend fun requestIdToken(serverClientId: String): String? {
        val activity = PomegranateGoogleSignIn.activity ?: return null
        val request =
            GetCredentialRequest
                .Builder()
                // Button-initiated flow, not One Tap: the account chooser always opens. One Tap
                // goes quiet for 24h once a user dismisses it a few times, which here would read
                // as the native path silently reverting to the browser.
                .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
                .build()
        val response =
            try {
                CredentialManager.create(activity).getCredential(activity, request)
            } catch (_: GetCredentialCancellationException) {
                throw PomegranatePopupClosedException()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Play services also reports an unregistered package / signing fingerprint here,
                // and not always as a GetCredentialException, so anything that is not a user
                // cancel hands the sign-in back to the browser flow.
                println("[Pomegranate] native sign-in unavailable: ${e::class.simpleName}: ${e.message}")
                return null
            }
        val credential = response.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            println("[Pomegranate] native sign-in returned ${credential.type}, not a Google ID token")
            return null
        }
        return try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: Exception) {
            println("[Pomegranate] native sign-in token unreadable: ${e.message}")
            null
        }
    }
}

/**
 * Holds the foreground activity the account picker needs to draw on. [MainActivity] registers
 * itself in onCreate; the reference is weak and cleared on destroy so a rotated-away activity
 * is not retained.
 */
object PomegranateGoogleSignIn {
    private var ref: WeakReference<ComponentActivity>? = null

    internal val activity: ComponentActivity? get() = ref?.get()

    fun register(activity: ComponentActivity) {
        ref = WeakReference(activity)
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    if (ref?.get() === activity) ref = null
                }
            },
        )
    }
}
