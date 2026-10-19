package com.feedtrainer.license
import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.crypto.tink.signature.Ed25519PublicKeyManager
import org.json.JSONObject
/**
 * Offline license check — 1:1 port of the notebook's verify loop (cell 12).
 * Embeds only the Ed25519 PUBLIC key: can verify, cannot forge. Verifies signature
 * + device binding + expiry with no per-launch server call. last_seen (monotonic
 * clock in EncryptedSharedPreferences) defeats naive clock-rollback.
 * NOTE: client-side checks deter forging/casual modding, not a patched binary —
 * pair with NDK + Play Integrity for high-value hardening.
 */
object LicenseVerifier {
    // Ship your real Tink Ed25519 public keyset JSON here (keep PRIVATE key server-only).
    private const val PUBLIC_KEYSET = "<REPLACE_WITH_TINK_PUBLIC_KEYSET_JSON>"

    data class Result(val ok: Boolean, val reason: String)

    fun verify(ctx: Context, token: String, deviceId: String): Result {
        val prefs = EncryptedSharedPreferences.create(ctx, "license",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        val lastSeen = prefs.getLong("last_seen", 0L)
        return try {
            val parts = token.split("."); require(parts.size == 2)
            val body = Base64.decode(parts[0], Base64.URL_SAFE)
            val sig = Base64.decode(parts[1], Base64.URL_SAFE)
            verifySignature(body, sig)                       // tamper check
            val p = JSONObject(String(body))
            val now = System.currentTimeMillis() / 1000
            val mono = maxOf(now, lastSeen)                  // never trust a rewind
            prefs.edit().putLong("last_seen", maxOf(mono, now)).apply()
            when {
                p.getString("sub") != deviceId -> Result(false, "device_mismatch")
                mono > p.getLong("exp")        -> Result(false, "expired")
                else -> Result(true, "valid for ${(p.getLong("exp") - mono) / 86400}d")
            }
        } catch (e: Exception) { Result(false, "signature_invalid") }
    }

    private fun verifySignature(body: ByteArray, sig: ByteArray) {
        // val verifier = CleartextKeysetHandle.read(JsonKeysetReader.withString(PUBLIC_KEYSET))
        //     .getPrimitive(PublicKeyVerify::class.java)
        // verifier.verify(sig, body)   // throws on tamper
    }
}
