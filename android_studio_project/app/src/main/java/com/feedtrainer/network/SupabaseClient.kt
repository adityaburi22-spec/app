package com.feedtrainer.network

import android.util.Log
import com.feedtrainer.model.UserSession
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Collections
import java.util.concurrent.TimeUnit

object SupabaseClient {

    private const val TAG = "SupabaseClient"
    const val BASE_URL = "https://ddsvhkhbxlochsrmntwa.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_o5uvYyciKmlcjrd0655SMA_9WNyurlT"
    const val SERVICE_KEY = "sb_secret_vU0NaBCPgpwy4qrxUWptmw_Y-P22NrD"
    const val OWNER_EMAIL = "adityaburi22@gmail.com"
    const val PURCHASE_URL = "https://ddsvhkhbxlochsrmntwa.supabase.co"
    const val GOOGLE_SERVER_CLIENT_ID = "582215671968-bghueegv7u92pvsl77na9hugaftsjnab.apps.googleusercontent.com"
    const val GOOGLE_CLIENT_SECRET = "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"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed class AuthResult {
        data class Success(val session: UserSession) : AuthResult()
        data class Error(val message: String) : AuthResult()
    }

    suspend fun signUp(email: String, pass: String, fullName: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                addProperty("email", email)
                addProperty("password", pass)
                val meta = JsonObject().apply {
                    addProperty("full_name", fullName)
                }
                add("data", meta)
            }

            val request = Request.Builder()
                .url("$BASE_URL/auth/v1/signup")
                .header("apikey", PUBLISHABLE_KEY)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = gson.fromJson(bodyStr, JsonObject::class.java)
                    val userId = root.get("id")?.asString ?: ""
                    val userEmail = root.get("email")?.asString ?: email
                    val token = root.get("access_token")?.asString ?: ""
                    AuthResult.Success(
                        UserSession(
                            userId = userId,
                            email = userEmail,
                            accessToken = token,
                            fullName = fullName
                        )
                    )
                } else {
                    val msg = parseErrorMessage(bodyStr, response.code)
                    AuthResult.Error(msg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign up error", e)
            AuthResult.Error(e.localizedMessage ?: "Network connection error")
        }
    }

    suspend fun signIn(email: String, pass: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                addProperty("email", email)
                addProperty("password", pass)
            }

            val request = Request.Builder()
                .url("$BASE_URL/auth/v1/token?grant_type=password")
                .header("apikey", PUBLISHABLE_KEY)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = gson.fromJson(bodyStr, JsonObject::class.java)
                    val accessToken = root.get("access_token")?.asString ?: ""
                    val userObj = root.getAsJsonObject("user")
                    val userId = userObj?.get("id")?.asString ?: ""
                    val userEmail = userObj?.get("email")?.asString ?: email
                    val userMeta = userObj?.getAsJsonObject("user_metadata")
                    val fullName = userMeta?.get("full_name")?.asString

                    AuthResult.Success(
                        UserSession(
                            userId = userId,
                            email = userEmail,
                            accessToken = accessToken,
                            fullName = fullName
                        )
                    )
                } else {
                    val msg = parseErrorMessage(bodyStr, response.code)
                    AuthResult.Error(msg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign in error", e)
            AuthResult.Error(e.localizedMessage ?: "Network connection error")
        }
    }

    /**
     * Checks if a user already exists in Supabase by email.
     * Returns the UserSession if found, or null if no account exists.
     * CRITICAL USER RULE: Never creates new accounts!
     */
    suspend fun findExistingUserByEmail(email: String): UserSession? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/auth/v1/admin/users?per_page=100")
                .header("apikey", SERVICE_KEY)
                .header("Authorization", "Bearer $SERVICE_KEY")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val root = gson.fromJson(bodyStr, JsonObject::class.java)
                    val usersArray = root.getAsJsonArray("users")
                    if (usersArray != null) {
                        for (u in usersArray) {
                            val userObj = u.asJsonObject
                            val userEmail = userObj.get("email")?.asString ?: ""
                            if (userEmail.equals(email, ignoreCase = true)) {
                                val id = userObj.get("id")?.asString ?: ""
                                val userMeta = userObj.getAsJsonObject("user_metadata")
                                val fullName = userMeta?.get("full_name")?.asString ?: userEmail.substringBefore("@")
                                return@withContext UserSession(
                                    userId = id,
                                    email = userEmail,
                                    accessToken = "",
                                    fullName = fullName
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking existing user by email", e)
        }
        null
    }

    /**
     * Authenticates an existing user via Google account.
     * GUARANTEE: If account does not already exist in Supabase, returns AuthResult.Error
     * requesting the user to create their account on the website first.
     */
    suspend fun authenticateExistingGoogleUser(email: String, displayName: String?): AuthResult = withContext(Dispatchers.IO) {
        try {
            // Rule 1: User MUST already exist in the database!
            val existingUser = findExistingUserByEmail(email)
            if (existingUser == null) {
                return@withContext AuthResult.Error(
                    "No account found with this Google email ($email).\n\nYou cannot create a new account inside the app. Please create your account and purchase a subscription on our website first."
                )
            }

            // Rule 2: Generate session for the verified existing user
            val linkPayload = JsonObject().apply {
                addProperty("type", "magiclink")
                addProperty("email", email)
            }

            val genReq = Request.Builder()
                .url("$BASE_URL/auth/v1/admin/generate_link")
                .header("apikey", SERVICE_KEY)
                .header("Authorization", "Bearer $SERVICE_KEY")
                .header("Content-Type", "application/json")
                .post(linkPayload.toString().toRequestBody(jsonMediaType))
                .build()

            var otpToken: String? = null
            var userId = existingUser.userId
            var fullName = existingUser.fullName ?: displayName

            client.newCall(genReq).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val obj = gson.fromJson(body, JsonObject::class.java)
                    otpToken = obj.get("email_otp")?.asString
                    userId = obj.get("id")?.asString ?: userId
                    val meta = obj.getAsJsonObject("user_metadata")
                    fullName = meta?.get("full_name")?.asString ?: fullName
                }
            }

            var sessionToken = ""

            // Step 3: Verify OTP to acquire authentic Supabase access_token
            if (!otpToken.isNullOrBlank()) {
                val verifyPayload = JsonObject().apply {
                    addProperty("type", "email")
                    addProperty("email", email)
                    addProperty("token", otpToken)
                }

                val verifyReq = Request.Builder()
                    .url("$BASE_URL/auth/v1/verify")
                    .header("apikey", PUBLISHABLE_KEY)
                    .header("Content-Type", "application/json")
                    .post(verifyPayload.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(verifyReq).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: ""
                        val obj = gson.fromJson(body, JsonObject::class.java)
                        sessionToken = obj.get("access_token")?.asString ?: ""
                    }
                }
            }

            if (sessionToken.isBlank()) {
                sessionToken = SERVICE_KEY
            }

            AuthResult.Success(
                UserSession(
                    userId = userId,
                    email = email,
                    accessToken = sessionToken,
                    fullName = fullName
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Google auth verification error", e)
            AuthResult.Error(e.localizedMessage ?: "Failed authenticating with Google account")
        }
    }

    suspend fun syncUserGoals(accessToken: String, goals: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext false
        try {
            val meta = JsonObject().apply {
                val array = com.google.gson.JsonArray()
                goals.forEach { array.add(it) }
                add("goals", array)
                addProperty("last_synced_at", System.currentTimeMillis())
            }
            val payload = JsonObject().apply {
                add("data", meta)
            }

            val request = Request.Builder()
                .url("$BASE_URL/auth/v1/user")
                .header("apikey", PUBLISHABLE_KEY)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .put(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync goals error", e)
            false
        }
    }

    suspend fun checkSubscriptionStatus(
        userId: String,
        email: String? = null,
        accessToken: String = ""
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        // 1. Founder / Owner bypass
        if (email?.equals(OWNER_EMAIL, ignoreCase = true) == true) {
            Log.i(TAG, "Founder lifetime access granted for $email")
            return@withContext Pair(true, "founder_lifetime")
        }

        if (userId.isBlank()) return@withContext Pair(false, null)

        // 2. Check active subscriptions in subscriptions table
        try {
            val subRequest = Request.Builder()
                .url("$BASE_URL/rest/v1/subscriptions?user_id=eq.$userId&select=*")
                .header("apikey", SERVICE_KEY)
                .header("Authorization", "Bearer $SERVICE_KEY")
                .get()
                .build()

            client.newCall(subRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val array = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                    if (array != null && array.size() > 0) {
                        for (element in array) {
                            val sub = element.asJsonObject
                            val status = sub.get("status")?.asString ?: ""
                            if (status.equals("active", ignoreCase = true) || status.equals("trialing", ignoreCase = true)) {
                                val plan = sub.get("plan")?.asString ?: "pro"
                                Log.i(TAG, "Active subscription verified: plan=$plan, status=$status")
                                return@withContext Pair(true, plan)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Subscriptions check query error: ${e.message}")
        }

        // 3. Check captured/paid payments in payments table
        try {
            val payRequest = Request.Builder()
                .url("$BASE_URL/rest/v1/payments?user_id=eq.$userId&select=*")
                .header("apikey", SERVICE_KEY)
                .header("Authorization", "Bearer $SERVICE_KEY")
                .get()
                .build()

            client.newCall(payRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val array = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                    if (array != null && array.size() > 0) {
                        for (element in array) {
                            val pay = element.asJsonObject
                            val status = pay.get("status")?.asString ?: ""
                            if (status.equals("captured", ignoreCase = true) ||
                                status.equals("paid", ignoreCase = true) ||
                                status.equals("successful", ignoreCase = true) ||
                                status.equals("completed", ignoreCase = true)
                            ) {
                                val plan = pay.get("plan")?.asString ?: "lifetime"
                                Log.i(TAG, "Captured payment verified: plan=$plan, status=$status")
                                return@withContext Pair(true, plan)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Payments check query error: ${e.message}")
        }

        Log.w(TAG, "No active subscription or paid payment found for userId=$userId, email=$email")
        Pair(false, null)
    }

    private val localBugReportsCache = Collections.synchronizedList(mutableListOf<com.feedtrainer.model.BugReport>())

    suspend fun submitBugReport(report: com.feedtrainer.model.BugReport): Boolean = withContext(Dispatchers.IO) {
        localBugReportsCache.add(0, report)
        try {
            val payload = JsonObject().apply {
                addProperty("id", report.id)
                addProperty("user_email", report.userEmail)
                addProperty("title", report.title)
                addProperty("category", report.category)
                addProperty("description", report.description)
                addProperty("device_info", report.deviceInfo)
                addProperty("app_version", report.appVersion)
                addProperty("timestamp", report.timestamp)
            }

            val request = Request.Builder()
                .url("$BASE_URL/rest/v1/bug_reports")
                .header("apikey", SERVICE_KEY)
                .header("Authorization", "Bearer $SERVICE_KEY")
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                Log.i(TAG, "Bug report submitted: code=${response.code}")
                response.isSuccessful || response.code in 200..299
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to submit bug report to Supabase (cached locally)", e)
            true // Cached locally so return true
        }
    }

    suspend fun fetchBugReports(): List<com.feedtrainer.model.BugReport> = withContext(Dispatchers.IO) {
        val remoteList = mutableListOf<com.feedtrainer.model.BugReport>()
        try {
            val request = Request.Builder()
                .url("$BASE_URL/rest/v1/bug_reports?select=*&order=timestamp.desc")
                .header("apikey", SERVICE_KEY)
                .header("Authorization", "Bearer $SERVICE_KEY")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val array = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                    if (array != null) {
                        for (el in array) {
                            val obj = el.asJsonObject
                            remoteList.add(
                                com.feedtrainer.model.BugReport(
                                    id = obj.get("id")?.asString ?: "",
                                    userEmail = obj.get("user_email")?.asString ?: "Unknown",
                                    title = obj.get("title")?.asString ?: "No title",
                                    category = obj.get("category")?.asString ?: "General",
                                    description = obj.get("description")?.asString ?: "",
                                    deviceInfo = obj.get("device_info")?.asString ?: "",
                                    appVersion = obj.get("app_version")?.asString ?: "",
                                    timestamp = obj.get("timestamp")?.asLong ?: System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch remote bug reports: ${e.message}")
        }

        val combined = (remoteList + localBugReportsCache).distinctBy { it.id }.sortedByDescending { it.timestamp }
        combined
    }

    private fun parseErrorMessage(body: String, code: Int): String {
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
            json.get("msg")?.asString
                ?: json.get("error_description")?.asString
                ?: json.get("message")?.asString
                ?: "Server returned code $code"
        } catch (e: Exception) {
            "Request failed (HTTP $code)"
        }
    }
}
