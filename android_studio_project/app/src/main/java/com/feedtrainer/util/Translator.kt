package com.feedtrainer.util

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap
import java.util.regex.Pattern

object Translator {

    private const val TAG = "Translator"
    private const val TIMEOUT_MS = 1800

    // Pure JVM and Android compatible LRU cache
    private val cache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(150, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > 250
            }
        }
    )

    /**
     * Checks if the text likely contains non-English characters or foreign script.
     */
    fun isLikelyNonEnglish(text: String): Boolean {
        if (text.isBlank()) return false
        for (ch in text) {
            if (ch.code > 127) {
                return true
            }
        }
        return false
    }

    /**
     * Translates foreign text to English using Google's public translation endpoint.
     * Thread-safe, cached in LRU cache, with a tight timeout to prevent latency in reel processing.
     * Returns the translated English string, or the original text if translation fails or times out.
     */
    fun translateToEnglish(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !isLikelyNonEnglish(trimmed)) {
            return trimmed
        }

        val cacheKey = trimmed.take(200)
        cache[cacheKey]?.let { return it }

        return try {
            val encodedQuery = URLEncoder.encode(trimmed.take(500), "UTF-8")
            val endpoint = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&q=$encodedQuery"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val translated = extractTranslatedText(response)
                if (translated.isNotEmpty()) {
                    cache[cacheKey] = translated
                    logDebug("Translated: \"${trimmed.take(40)}\" -> \"${translated.take(40)}\"")
                    return translated
                }
            }
            trimmed
        } catch (e: Exception) {
            logWarn("Translation error/timeout for: \"${trimmed.take(30)}\" (${e.message})")
            trimmed
        }
    }

    /**
     * Parses the Google translate JSON format: [[["translated","source",...],...],...]
     */
    private fun extractTranslatedText(json: String): String {
        val sb = StringBuilder()
        // Regex extracts text inside the first array element string blocks
        val pattern = Pattern.compile("\\[\\[\\[\"(.*?)\",\"")
        val matcher = pattern.matcher(json)
        if (matcher.find()) {
            sb.append(matcher.group(1)?.replace("\\\"", "\"")?.replace("\\n", " "))
        } else {
            // Fallback: match any quoted strings in the first sub-array
            val firstSubArrayEnd = json.indexOf("]],")
            val sub = if (firstSubArrayEnd > 0) json.substring(0, firstSubArrayEnd) else json
            val itemPattern = Pattern.compile("\"(.*?)\"")
            val itemMatcher = itemPattern.matcher(sub)
            if (itemMatcher.find()) {
                sb.append(itemMatcher.group(1))
            }
        }
        return sb.toString().trim()
    }

    private fun logDebug(msg: String) {
        try {
            android.util.Log.d(TAG, msg)
        } catch (_: Throwable) {
            println("[$TAG DEBUG] $msg")
        }
    }

    private fun logWarn(msg: String) {
        try {
            android.util.Log.w(TAG, msg)
        } catch (_: Throwable) {
            println("[$TAG WARN] $msg")
        }
    }
}
