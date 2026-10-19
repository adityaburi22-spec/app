package com.feedtrainer.license

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

object LicenseVerifier {

    data class Result(val ok: Boolean, val reason: String)

    fun verify(ctx: Context, token: String, deviceId: String): Result {
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                ctx,
                "feedtrainer_license_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val lastSeen = prefs.getLong("last_seen", 0L)
            val parts = token.split(".")
            require(parts.size == 2) { "Malformed license token" }

            val body = Base64.decode(parts[0], Base64.URL_SAFE)
            val payload = JSONObject(String(body))

            val now = System.currentTimeMillis() / 1000
            val mono = maxOf(now, lastSeen)
            prefs.edit().putLong("last_seen", maxOf(mono, now)).apply()

            when {
                payload.optString("sub") != deviceId -> Result(false, "Device mismatch")
                mono > payload.optLong("exp") -> Result(false, "License expired")
                else -> {
                    val daysLeft = (payload.optLong("exp") - mono) / 86400
                    Result(true, "Active license ($daysLeft days remaining)")
                }
            }
        } catch (e: Exception) {
            Result(false, "Invalid license: ${e.message}")
        }
    }
}
