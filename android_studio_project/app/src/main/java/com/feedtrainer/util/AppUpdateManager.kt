package com.feedtrainer.util

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.feedtrainer.model.AppReleaseUpdate
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_REPO = "adityaburi22-spec/app"
    private const val GITHUB_TOKEN = "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"
    private const val RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Checks if a newer release exists on GitHub.
     */
    suspend fun checkForUpdate(currentVersionName: String): AppReleaseUpdate? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("User-Agent", "FeedTrainer-App")
                .header("Authorization", "token $GITHUB_TOKEN")
                .header("Accept", "application/vnd.github.v3+json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub releases API returned code: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val json = gson.fromJson(body, JsonObject::class.java)

                val tagName = json.get("tag_name")?.asString ?: ""
                val releaseName = json.get("name")?.asString ?: tagName
                val bodyNotes = json.get("body")?.asString ?: "Bug fixes and performance enhancements."

                // Find APK asset download url
                var apkUrl: String? = null
                val assets = json.getAsJsonArray("assets")
                if (assets != null) {
                    for (asset in assets) {
                        val assetObj = asset.asJsonObject
                        val name = assetObj.get("name")?.asString ?: ""
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            // Release APK asset preferred
                            apkUrl = assetObj.get("browser_download_url")?.asString
                            if (name.contains("release", ignoreCase = true)) {
                                break
                            }
                        }
                    }
                }

                if (apkUrl.isNullOrBlank()) {
                    Log.w(TAG, "No APK asset found in latest release $tagName")
                    return@withContext null
                }

                val cleanLatest = tagName.trim().removePrefix("v").replace("-beta", "").replace("-alpha", "")
                val cleanCurrent = currentVersionName.trim().removePrefix("v").replace("-beta", "").replace("-alpha", "")

                val isNewer = isVersionGreater(cleanLatest, cleanCurrent)
                Log.i(TAG, "Update check: latest=$cleanLatest ($tagName), current=$cleanCurrent, isNewer=$isNewer")

                if (isNewer) {
                    AppReleaseUpdate(
                        tagName = tagName,
                        versionName = releaseName,
                        releaseNotes = bodyNotes,
                        downloadUrl = apkUrl,
                        isMandatory = true
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update: ${e.message}")
            null
        }
    }

    private fun isVersionGreater(latest: String, current: String): Boolean {
        if (latest == current) return false
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /**
     * Downloads APK asset from downloadUrl with progress updates.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "FeedTrainer-App")
                .header("Authorization", "token $GITHUB_TOKEN")
                .header("Accept", "application/octet-stream")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "APK download failed with HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body ?: return@withContext null
                val contentLength = body.contentLength()

                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
                val file = File(dir, "FeedTrainer-update.apk")
                if (file.exists()) file.delete()

                body.byteStream().use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytes = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                            if (contentLength > 0) {
                                val percent = ((totalBytes * 100) / contentLength).toInt()
                                mainHandler.post { onProgress(percent) }
                            }
                        }
                        output.flush()
                    }
                }
                Log.i(TAG, "APK downloaded successfully to ${file.absolutePath}")
                file
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading update APK", e)
            null
        }
    }

    /**
     * Launches Android Package Installer for the downloaded APK via FileProvider.
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching package installer", e)
        }
    }
}
