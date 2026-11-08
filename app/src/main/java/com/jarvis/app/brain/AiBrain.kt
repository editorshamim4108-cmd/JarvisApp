package com.jarvis.app.brain

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AiBrain {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val chatHistory = mutableListOf<Pair<String, String>>()

    var claudeApiKey: String = ""

    suspend fun ask(userInput: String, screenContext: String, locale: String): String {
        return withContext(Dispatchers.IO) {
            val isHindi = locale.startsWith("hi")
            val systemPrompt = buildSystemPrompt(isHindi, screenContext)

            var response: String? = null

            if (claudeApiKey.isNotBlank()) {
                response = tryClaudeApi(systemPrompt, userInput)
            }

            if (response == null) {
                val messages = buildOpenAiMessages(systemPrompt, userInput)
                for (model in listOf("openai", "openai-fast")) {
                    response = tryPollinationsPost(model, messages)
                    if (response != null) break
                }
            }

            if (response != null) {
                chatHistory.add("user" to userInput)
                chatHistory.add("assistant" to response)
                while (chatHistory.size > 16) chatHistory.removeAt(0)
                response
            } else {
                "Boss, AI সার্ভার এখন ব্যস্ত আছে। একটু পরে আবার জিজ্ঞাসা করুন।"
            }
        }
    }

    private fun tryClaudeApi(systemPrompt: String, userInput: String): String? {
        return try {
            val messages = JSONArray()
            val recentHistory = chatHistory.takeLast(8)
            for ((role, content) in recentHistory) {
                messages.put(JSONObject().apply {
                    put("role", role)
                    put("content", content)
                })
            }
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userInput)
            })

            val body = JSONObject().apply {
                put("model", "claude-haiku-4-0")
                put("max_tokens", 300)
                put("system", systemPrompt)
                put("messages", messages)
            }

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", claudeApiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Claude error: ${response.code} body=${bodyStr.take(300)}")
                return null
            }

            val json = JSONObject(bodyStr)
            val contentArray = json.optJSONArray("content")
            if (contentArray != null && contentArray.length() > 0) {
                contentArray.optJSONObject(0)
                    ?.optString("text", "")
                    ?.trim()
                    ?.ifEmpty { null }
            } else {
                Log.e(TAG, "Claude: no content in response")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Claude exception", e)
            null
        }
    }

    private fun buildSystemPrompt(isHindi: Boolean, screenContext: String): String {
        return if (isHindi) {
            buildString {
                append("तुम JARVIS (जार्विस) हो, उपयोगकर्ता के बुद्धिमान व्यक्तिगत AI सहायक। ")
                append("उपयोगकर्ता को 'Boss' कहो। ")
                append("तुम्हारे पास उपयोगकर्ता की मोबाइल स्क्रीन पढ़ने की पूरी क्षमता है")
                if (screenContext.isNotBlank()) {
                    append(" (स्क्रीन टेक्स्ट: \"${screenContext.take(300)}\")")
                }
                append("। कभी यह मत कहना कि 'मैं आपकी स्क्रीन नहीं देख सकता'। ")
                append("हमेशा 1 से 3 वाक्यों में सीधा और सटीक उत्तर दो।")
            }
        } else {
            buildString {
                append("তুমি JARVIS (জার্ভিস), ব্যবহারকারীর অত্যন্ত বুদ্ধিমান ব্যক্তিগত AI সহকারী। ")
                append("ব্যবহারকারীকে 'Boss' বলে সম্বোধন করবে। ")
                append("তোমার কাছে ব্যবহারকারীর মোবাইলের স্ক্রিন দেখার ও পড়ার সম্পূর্ণ ক্ষমতা আছে")
                if (screenContext.isNotBlank()) {
                    append(" (বর্তমান স্ক্রিনের লেখা: \"${screenContext.take(300)}\")")
                }
                append("। কখনোই বলবে না 'আমি স্ক্রিন দেখতে পারি না'। ")
                append("সরাসরি, সঠিক ও সাবলীল বাংলায় ১-৩ বাক্যে উত্তর দাও। ")
                append("ব্যবহারকারী যা বলেছে সেটা repeat করবে না।")
            }
        }
    }

    private fun buildOpenAiMessages(systemPrompt: String, userInput: String): JSONArray {
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        val recentHistory = chatHistory.takeLast(8)
        for ((role, content) in recentHistory) {
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
        }

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userInput)
        })

        return messages
    }

    private fun tryPollinationsPost(model: String, messages: JSONArray): String? {
        return try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.7)
            }

            val request = Request.Builder()
                .url("https://text.pollinations.ai/openai")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Error ($model): ${response.code} body=${bodyStr.take(200)}")
                return null
            }

            if (bodyStr.contains("reached its budget", ignoreCase = true) ||
                bodyStr.contains("rate limit", ignoreCase = true)) {
                Log.w(TAG, "Budget/rate error ($model): ${bodyStr.take(200)}")
                return null
            }

            try {
                val json = JSONObject(bodyStr)
                val content = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                    ?.ifEmpty { null }

                if (content != null && content.contains("reached its budget", ignoreCase = true)) {
                    Log.w(TAG, "Budget error in content ($model)")
                    return null
                }
                content
            } catch (e: Exception) {
                Log.e(TAG, "Parse error ($model)", e)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception ($model)", e)
            null
        }
    }

    fun clearHistory() {
        chatHistory.clear()
    }

    companion object {
        private const val TAG = "AiBrain"
    }
}
