package com.feedtrainer.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.feedtrainer.model.ChannelReputation
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap

class ChannelMemoryStore(context: Context) {

    companion object {
        private const val TAG = "ChannelMemoryStore"
        private const val PREFS_NAME = "feedtrainer_channel_memory"
        private const val KEY_CHANNELS = "cached_channels"

        @Volatile
        private var INSTANCE: ChannelMemoryStore? = null

        fun getInstance(context: Context): ChannelMemoryStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChannelMemoryStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val memoryCache = ConcurrentHashMap<String, ChannelReputation>()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val json = prefs.getString(KEY_CHANNELS, null) ?: return
        try {
            val type = object : TypeToken<Map<String, ChannelReputation>>() {}.type
            val map: Map<String, ChannelReputation> = gson.fromJson(json, type) ?: emptyMap()
            memoryCache.putAll(map)
            Log.d(TAG, "Loaded ${memoryCache.size} channels into memory cache")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load channel memory from disk", e)
        }
    }

    @Synchronized
    private fun persistToDisk() {
        try {
            // Keep top 300 most recently seen channels
            val trimmed = memoryCache.values
                .sortedByDescending { it.lastSeen }
                .take(300)
                .associateBy { it.handle.lowercase().trim() }

            prefs.edit().putString(KEY_CHANNELS, gson.toJson(trimmed)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist channel memory", e)
        }
    }

    private fun normalize(handle: String): String {
        return handle.lowercase().trim().removePrefix("@")
    }

    fun getReputation(handle: String): ChannelReputation? {
        val key = normalize(handle)
        if (key.isBlank()) return null
        return memoryCache[key]
    }

    fun getChannelScore(handle: String): Float {
        val rep = getReputation(handle) ?: return 0f
        return rep.score
    }

    fun isBlacklisted(handle: String): Boolean {
        val rep = getReputation(handle) ?: return false
        return rep.isBlacklisted || rep.negativeSwipes >= 3
    }

    fun isWhitelisted(handle: String): Boolean {
        val rep = getReputation(handle) ?: return false
        return rep.isWhitelisted || (rep.positiveLikes >= 3 && rep.negativeSwipes == 0)
    }

    fun recordPositiveLike(handle: String) {
        val key = normalize(handle)
        if (key.isBlank()) return

        val rep = memoryCache.getOrPut(key) { ChannelReputation(handle = key) }
        rep.positiveLikes += 1
        rep.lastSeen = System.currentTimeMillis()
        if (rep.positiveLikes >= 3 && rep.negativeSwipes == 0) {
            rep.isWhitelisted = true
        }
        persistToDisk()
        Log.d(TAG, "Channel @$key positive hit: ${rep.positiveLikes} (whitelisted: ${rep.isWhitelisted})")
    }

    fun recordFastSwipe(handle: String) {
        val key = normalize(handle)
        if (key.isBlank()) return

        val rep = memoryCache.getOrPut(key) { ChannelReputation(handle = key) }
        rep.negativeSwipes += 1
        rep.lastSeen = System.currentTimeMillis()
        if (rep.negativeSwipes >= 3) {
            rep.isBlacklisted = true
            rep.isWhitelisted = false
        }
        persistToDisk()
        Log.d(TAG, "Channel @$key fast swipe (<1.8s): ${rep.negativeSwipes} (blacklisted: ${rep.isBlacklisted})")
    }

    fun recordManualUnlike(handle: String) {
        val key = normalize(handle)
        if (key.isBlank()) return

        val rep = memoryCache.getOrPut(key) { ChannelReputation(handle = key) }
        rep.manualUnlikes += 1
        rep.isBlacklisted = true
        rep.isWhitelisted = false
        rep.lastSeen = System.currentTimeMillis()
        persistToDisk()
        Log.d(TAG, "Channel @$key manually un-liked! Immediate blacklist applied.")
    }

    fun recordManualLike(handle: String) {
        val key = normalize(handle)
        if (key.isBlank()) return

        val rep = memoryCache.getOrPut(key) { ChannelReputation(handle = key) }
        rep.manualLikes += 1
        rep.isBlacklisted = false
        rep.lastSeen = System.currentTimeMillis()
        persistToDisk()
        Log.d(TAG, "Channel @$key manually liked! Reputation boosted.")
    }

    fun getAllReputations(): List<ChannelReputation> {
        return memoryCache.values.sortedByDescending { it.lastSeen }
    }

    fun clearReputations() {
        memoryCache.clear()
        prefs.edit().remove(KEY_CHANNELS).apply()
    }
}
