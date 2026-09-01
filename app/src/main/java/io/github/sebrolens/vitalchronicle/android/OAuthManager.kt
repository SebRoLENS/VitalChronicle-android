package io.github.sebrolens.vitalchronicle.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64

class OAuthManager(
    private val vault: CredentialVault,
    private val http: AndroidHttpClient,
) {
    suspend fun authenticate(activity: Activity, scopes: Set<String>): OAuthToken = withContext(Dispatchers.IO) {
        val credentials = requireNotNull(vault.credentials()) { "Import the OAuth JSON first." }
        val stateBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)
        ServerSocket(8765).use { server ->
            server.soTimeout = 5 * 60 * 1000
            val authUrl = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
                .appendQueryParameter("client_id", credentials.clientId)
                .appendQueryParameter("redirect_uri", credentials.redirectUri)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", scopes.joinToString(" "))
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", "consent")
                .appendQueryParameter("include_granted_scopes", "true")
                .appendQueryParameter("state", state)
                .build()
            withContext(Dispatchers.Main) { activity.startActivity(Intent(Intent.ACTION_VIEW, authUrl)) }

            val authorizationCode = server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: error("OAuth callback was empty.")
                val target = requestLine.split(" ").getOrNull(1) ?: error("Invalid OAuth callback.")
                val uri = Uri.parse("http://localhost$target")
                val returnedState = uri.getQueryParameter("state")
                val error = uri.getQueryParameter("error")
                val code = uri.getQueryParameter("code")
                val body = if (error != null) {
                    "VitalChronicle authorization was cancelled: $error"
                } else {
                    "Authorization received. Return to VitalChronicle to finish connecting your account."
                }
                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n<html><body><h2>$body</h2></body></html>"
                socket.getOutputStream().write(response.toByteArray())
                require(returnedState == state) { "OAuth state verification failed." }
                require(!code.isNullOrBlank()) { error ?: "Google did not return an authorization code." }
                code
            }

            // The localhost callback reaches this process while the external browser is
            // still in the foreground. On recent Android versions a background app can
            // temporarily have no usable default network, which made the immediate token
            // exchange fail with UnknownHostException even though the browser had Internet.
            // Wait until VitalChronicle is foreground/resumed before contacting Google.
            awaitActivityResumed(activity)

            val token = exchangeCode(credentials, authorizationCode)
            vault.saveToken(token)
            token
        }
    }

    suspend fun validAccessToken(): String = withContext(Dispatchers.IO) {
        val credentials = requireNotNull(vault.credentials()) { "OAuth credentials missing." }
        val token = requireNotNull(vault.token()) { "Google account is not connected." }
        if (token.expiresAtEpoch > System.currentTimeMillis() / 1000 + 90) return@withContext token.accessToken
        val refresh = token.refreshToken ?: error("Google session cannot be refreshed; connect again.")
        val body = FormBody.Builder()
            .add("client_id", credentials.clientId).add("client_secret", credentials.clientSecret)
            .add("refresh_token", refresh).add("grant_type", "refresh_token").build()
        val req = Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()
        val json = executeJson(req)
        val refreshed = OAuthToken(
            json.getString("access_token"), refresh,
            System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600)
        )
        vault.saveToken(refreshed)
        refreshed.accessToken
    }

    private suspend fun awaitActivityResumed(activity: Activity) {
        val owner = activity as? LifecycleOwner ?: return
        withContext(Dispatchers.Main) {
            while (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                delay(100)
            }
        }
    }

    private fun exchangeCode(c: OAuthCredentials, code: String): OAuthToken {
        val body = FormBody.Builder().add("code", code).add("client_id", c.clientId)
            .add("client_secret", c.clientSecret).add("redirect_uri", c.redirectUri)
            .add("grant_type", "authorization_code").build()
        val req = Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()
        val json = executeJson(req)
        return OAuthToken(
            json.getString("access_token"), json.optNullableString("refresh_token"),
            System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600)
        )
    }

    private fun executeJson(request: Request): JSONObject {
        http.execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Google OAuth ${response.code}: $text")
            return JSONObject(text)
        }
    }
}
