package dev.aarso.mail.app.platform

import android.accounts.Account as AndroidAccount
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dev.aarso.mail.app.BuildConfig
import dev.aarso.mail.model.Account
import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.net.AuthenticationException
import dev.aarso.mail.sync.SecretStore
import dev.aarso.mail.sync.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import net.openid.appauth.AuthorizationRequest as AppAuthRequest

/** A sign-in that needs the person: launch [intent] (Google) or [pendingIntent] (consent) from an Activity. */
sealed interface ConsentNeeded {
    val accountEmail: String
    data class Google(override val accountEmail: String, val pendingIntent: PendingIntent) : ConsentNeeded
}

class ConsentRequiredException(val consent: ConsentNeeded) : AuthenticationException("Sign-in needs your confirmation")

/**
 * OAuth for the two providers that require it.
 *
 * **Gmail** uses Google's on-device Authorization API (Play services): no client secret, no backend,
 * consent shown by the system, tokens refreshed by Play services. It needs the app registered once in
 * a Google Cloud project (package name + signing certificate) with the `https://mail.google.com/`
 * scope — see README → "Sign-in setup". Until then, Gmail works with an app password.
 *
 * **Microsoft** (Outlook.com and Microsoft 365) uses AppAuth with PKCE against the Microsoft identity
 * platform, with the refreshable [AuthState] sealed in the Keystore. Its client id comes from
 * `MICROSOFT_CLIENT_ID` in local.properties.
 */
class AndroidTokenProvider(
    private val context: Context,
    private val secrets: SecretStore,
) : TokenProvider {

    private val _consents = MutableSharedFlow<ConsentNeeded>(extraBufferCapacity = 4)
    /** The UI collects this and launches consent screens; background sync just marks the account. */
    val consents: SharedFlow<ConsentNeeded> = _consents

    private val lastGoogleToken = HashMap<String, String>()

    override suspend fun accessToken(account: Account, forceRefresh: Boolean): String = when (account.oauthProvider) {
        "google" -> googleToken(account.email, forceRefresh)
        "microsoft" -> microsoftToken(account.secret, forceRefresh)
        else -> throw AuthenticationException("No OAuth provider configured for ${account.email}")
    }

    // ---- Google ----

    private fun googleRequest(email: String) = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(GMAIL_SCOPE)))
        .setAccount(AndroidAccount(email, "com.google"))
        .build()

    private suspend fun googleToken(email: String, forceRefresh: Boolean): String {
        if (forceRefresh) {
            lastGoogleToken[email]?.let { t -> withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(context, t) } } }
        }
        val result = try {
            Identity.getAuthorizationClient(context).authorize(googleRequest(email)).await()
        } catch (e: Exception) {
            throw AuthenticationException("Google sign-in isn't available: ${e.message}. Use an app password, or see README → Sign-in setup.")
        }
        val token = result.accessToken
        if (token != null && !result.hasResolution()) {
            lastGoogleToken[email] = token
            return token
        }
        val pending = result.pendingIntent ?: throw AuthenticationException("Google did not return a token")
        val consent = ConsentNeeded.Google(email, pending)
        _consents.tryEmit(consent)
        throw ConsentRequiredException(consent)
    }

    /** Finish a Google consent the UI launched; returns the token so setup can verify the account. */
    fun completeGoogleConsent(email: String, data: Intent?): String? {
        val result = runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data) }.getOrNull() ?: return null
        return result.accessToken?.also { lastGoogleToken[email] = it }
    }

    // ---- Microsoft ----

    private val msConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"),
        Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/token"),
    )

    val microsoftConfigured: Boolean get() = BuildConfig.MICROSOFT_CLIENT_ID.isNotBlank()

    /** The consent screen intent for a new Microsoft account; its result goes to [completeMicrosoft]. */
    fun microsoftSignInIntent(activity: Activity, loginHint: String): Intent {
        val req = AppAuthRequest.Builder(msConfig, BuildConfig.MICROSOFT_CLIENT_ID, ResponseTypeValues.CODE, Uri.parse(MS_REDIRECT))
            .setScopes(MS_SCOPES)
            .setLoginHint(loginHint)
            .setPrompt("select_account")
            .build()
        return AuthorizationService(activity).getAuthorizationRequestIntent(req)
    }

    /** Exchange the code, seal the refreshable state under [ref], and return a fresh access token. */
    suspend fun completeMicrosoft(data: Intent, ref: SecretRef): String {
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        if (response == null) throw AuthenticationException("Microsoft sign-in was cancelled", error?.errorDescription)
        val state = AuthState(response, error)
        val service = AuthorizationService(context)
        try {
            val token = suspendCancellableCoroutine { cont ->
                service.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, ex ->
                    if (tokenResponse != null) {
                        state.update(tokenResponse, ex)
                        cont.resume(tokenResponse.accessToken ?: "")
                    } else cont.resumeWithException(AuthenticationException("Microsoft sign-in failed", ex?.errorDescription))
                }
            }
            secrets.put(ref, state.jsonSerializeString())
            return token
        } finally {
            service.dispose()
        }
    }

    private suspend fun microsoftToken(ref: SecretRef, forceRefresh: Boolean): String {
        val json = secrets.get(ref) ?: throw AuthenticationException("Sign in to Microsoft again")
        val state = AuthState.jsonDeserialize(json)
        if (forceRefresh) state.needsTokenRefresh = true
        val service = AuthorizationService(context)
        try {
            val token = suspendCancellableCoroutine { cont ->
                state.performActionWithFreshTokens(service) { accessToken, _, ex ->
                    if (accessToken != null) cont.resume(accessToken)
                    else cont.resumeWithException(AuthenticationException("Microsoft sign-in expired — sign in again", ex?.errorDescription))
                }
            }
            secrets.put(ref, state.jsonSerializeString())
            return token
        } finally {
            service.dispose()
        }
    }

    companion object {
        const val GMAIL_SCOPE = "https://mail.google.com/"
        const val MS_REDIRECT = "com.asoc.youvegotmail://oauth2redirect"
        val MS_SCOPES = listOf(
            "offline_access", "openid", "email", "profile",
            "https://outlook.office.com/IMAP.AccessAsUser.All",
            "https://outlook.office.com/SMTP.Send",
        )
    }
}
