package io.github.sebrolens.vitalchronicle.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.Locale

class GoogleAuthorizationRequiredException(message: String) : IllegalStateException(message)

class GoogleAuthorizationManager(
    private val context: Context,
    private val vault: CredentialVault,
) {
    private val client = Identity.getAuthorizationClient(context)
    private var cachedAccessToken: String? = null
    private var cachedAtMillis: Long = 0L

    private fun request(scopes: Set<String>, selectAccount: Boolean): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(scopes.sorted().map(::Scope))
            .apply {
                if (selectAccount) setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
            }
            .build()

    /** Starts an explicit user connection. Null means authorization completed silently. */
    suspend fun beginAuthorization(scopes: Set<String>): PendingIntent? {
        val result = client.authorize(request(scopes, selectAccount = true)).await()
        return if (result.hasResolution()) {
            result.pendingIntent ?: error("Google authorization requested interaction but returned no PendingIntent.")
        } else {
            acceptResult(result)
            null
        }
    }

    /** Completes the PendingIntent launched by the Activity Result API. */
    fun completeAuthorization(data: Intent?): String {
        requireNotNull(data) { "Google authorization returned no result." }
        return acceptResult(client.getAuthorizationResultFromIntent(data))
    }

    /**
     * Returns a client-side OAuth access token. Google Play services owns refresh and
     * account state; no OAuth client secret or refresh token is embedded in VitalChronicle.
     */
    suspend fun validAccessToken(scopes: Set<String>): String {
        val now = System.currentTimeMillis()
        cachedAccessToken?.takeIf { now - cachedAtMillis < 45L * 60L * 1000L }?.let { return it }

        val result = client.authorize(request(scopes, selectAccount = false)).await()
        if (result.hasResolution()) {
            throw GoogleAuthorizationRequiredException(
                "Google authorization needs user interaction. Open Settings and tap Connect Google account."
            )
        }
        return acceptResult(result)
    }

    suspend fun clearCachedToken(token: String) {
        cachedAccessToken = null
        cachedAtMillis = 0L
        runCatching {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        }
    }

    suspend fun disconnect(scopes: Set<String>) {
        val token = cachedAccessToken
        cachedAccessToken = null
        cachedAtMillis = 0L
        if (!token.isNullOrBlank()) {
            runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
        }
        runCatching {
            client.revokeAccess(
                RevokeAccessRequest.builder().setScopes(scopes.sorted().map(::Scope)).build()
            ).await()
        }
        vault.setNativeGoogleConnected(false)
    }

    private fun acceptResult(result: AuthorizationResult): String {
        val token = result.accessToken?.takeIf { it.isNotBlank() }
            ?: error("Google authorization completed without an access token.")
        cachedAccessToken = token
        cachedAtMillis = System.currentTimeMillis()
        vault.setNativeGoogleConnected(true)
        return token
    }

    companion object {
        fun signingSha1(context: Context): String {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = requireNotNull(packageInfo.signingInfo)
                val signatures = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
                signatures.first()
            } else {
                @Suppress("DEPRECATION")
                requireNotNull(packageInfo.signatures).first()
            }
            return MessageDigest.getInstance("SHA-1")
                .digest(signature.toByteArray())
                .joinToString(":") { byte -> String.format(Locale.US, "%02X", byte.toInt() and 0xFF) }
        }
    }
}
