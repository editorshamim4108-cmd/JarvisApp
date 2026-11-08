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
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val chatHistory = mutableListOf<Pair<String, String>>()

    suspend fun ask(userInput: String, screenContext: String, locale: String): String {
        return withContext(Dispatchers.IO) {
            val isHindi = locale.startsWith("hi")

            val systemPrompt = if (isHindi) {
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

            var response: String? = null

            // Try 1: POST to /openai (OpenAI-compatible format)
            response = tryPollinationsOpenAI(messages)

            // Try 2: POST to / (plain text response)
            if (response == null) {
                response = tryPollinationsPlainPost(messages)
            }

            // Try 3: GET endpoint (simplest, most reliable)
            if (response == null) {
                response = tryPollinationsGet(systemPrompt, userInput)
            }

            if (response != null) {
                chatHistory.add("user" to userInput)
                chatHistory.add("assistant" to response)
                while (chatHistory.size > 16) {
                    chatHistory.removeAt(0)
                }
                response
            } else {
                "Boss, AI সার্ভার এখন ব্যস্ত আছে। একটু পরে আবার জিজ্ঞাসা করুন।"
            }
        }
    }

    private fun tryPollinationsOpenAI(messages: JSONArray): String? {
        return try {
            val body = JSONObject().apply {
                put("model", "openai")
                put("messages", messages)
                put("temperature", 0.7)
            }

            val request = Request.Builder()
                .url("https://text.pollinations.ai/openai")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            when {
                response.isSuccessful -> {
                    val bodyStr = response.body?.string() ?: ""
                    try {
                        val json = JSONObject(bodyStr)
                        val choices = json.optJSONArray("choices")
                        choices?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "")
                            ?.trim()
                            ?.ifEmpty { null }
                    } catch (e: Exception) {
                        bodyStr.trim().ifEmpty { null }
                    }
                }
                response.code == 402 -> {
                    Log.w(TAG, "Pollinations rate limited (402)")
                    null
                }
                else -> {
                    Log.e(TAG, "Pollinations /openai error: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pollinations /openai exception", e)
            null
        }
    }

    private fun tryPollinationsPlainPost(messages: JSONArray): String? {
        return try {
            val body = JSONObject().apply {
                put("messages", messages)
            }

            val request = Request.Builder()
                .url("https://text.pollinations.ai/")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.trim()?.ifEmpty { null }
            } else {
                Log.e(TAG, "Pollinations POST / error: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pollinations POST / exception", e)
            null
        }
    }

    private fun tryPollinationsGet(systemPrompt: String, userInput: String): String? {
        return try {
            val query = java.net.URLEncoder.encode(userInput, "UTF-8")
            val system = java.net.URLEncoder.encode(
                systemPrompt.take(500), "UTF-8"
            )
            val request = Request.Builder()
                .url("https://text.pollinations.ai/$query?system=$system")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.trim()?.ifEmpty { null }
            } else {
                Log.e(TAG, "Pollinations GET error: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pollinations GET exception", e)
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
