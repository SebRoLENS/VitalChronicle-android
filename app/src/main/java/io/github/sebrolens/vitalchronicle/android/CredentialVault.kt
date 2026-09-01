package io.github.sebrolens.vitalchronicle.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Legacy desktop-style OAuth data kept only so upgrades can safely read old installs. */
data class OAuthCredentials(val clientId: String, val clientSecret: String, val redirectUri: String)
data class OAuthToken(val accessToken: String, val refreshToken: String?, val expiresAtEpoch: Long)

class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("secure_vitalchronicle", Context.MODE_PRIVATE)
    private val alias = "vitalchronicle_android_aes"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val raw = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? = runCatching {
        val raw = Base64.decode(value, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val body = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    fun setNativeGoogleConnected(connected: Boolean) =
        prefs.edit().putBoolean("native_google_connected", connected).apply()

    fun nativeGoogleConnected(): Boolean = prefs.getBoolean("native_google_connected", false)

    // Legacy migration helpers. Native Android authorization no longer consumes these values.
    fun saveCredentials(c: OAuthCredentials) = prefs.edit().putString("credentials", encrypt(JSONObject().apply {
        put("client_id", c.clientId); put("client_secret", c.clientSecret); put("redirect_uri", c.redirectUri)
    }.toString())).apply()

    fun credentials(): OAuthCredentials? {
        val raw = prefs.getString("credentials", null)?.let(::decrypt) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()?.let {
            OAuthCredentials(it.getString("client_id"), it.getString("client_secret"), it.getString("redirect_uri"))
        }
    }

    fun saveToken(t: OAuthToken) = prefs.edit().putString("token", encrypt(JSONObject().apply {
        put("access_token", t.accessToken); put("refresh_token", t.refreshToken ?: JSONObject.NULL); put("expires_at", t.expiresAtEpoch)
    }.toString())).apply()

    fun token(): OAuthToken? {
        val raw = prefs.getString("token", null)?.let(::decrypt) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()?.let {
            OAuthToken(it.getString("access_token"), it.optNullableString("refresh_token"), it.getLong("expires_at"))
        }
    }

    fun clearLegacyOAuth() = prefs.edit().remove("credentials").remove("token").apply()
    fun clearToken() = prefs.edit().remove("token").apply()
    fun clearAll() = prefs.edit().clear().apply()

    companion object {
        fun parseCredentialJson(text: String): OAuthCredentials {
            val root = JSONObject(text)
            val section = when {
                root.has("web") -> root.getJSONObject("web")
                root.has("installed") -> root.getJSONObject("installed")
                else -> root
            }
            val clientId = section.getString("client_id")
            val secret = section.optString("client_secret")
            require(secret.isNotBlank()) { "This OAuth JSON has no client_secret." }
            val redirects = section.optJSONArray("redirect_uris") ?: JSONArrayCompat.empty()
            var selected: String? = null
            for (i in 0 until redirects.length()) {
                val uri = redirects.optString(i)
                if (uri == "http://localhost:8765/" || uri == "http://127.0.0.1:8765/") { selected = uri; break }
            }
            require(selected != null) { "OAuth client must include http://localhost:8765/ as a redirect URI, like VitalChronicle desktop." }
            return OAuthCredentials(clientId, secret, selected!!)
        }
    }
}

private object JSONArrayCompat { fun empty() = org.json.JSONArray() }
