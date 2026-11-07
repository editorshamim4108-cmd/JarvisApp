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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val chatHistory = mutableListOf<Pair<String, String>>() // role, content

    suspend fun ask(userInput: String, screenContext: String, locale: String): String {
        return withContext(Dispatchers.IO) {
            val isHindi = locale.startsWith("hi")

            val systemPrompt = if (isHindi) {
                buildString {
                    append("तुम JARVIS (जार्विस) हो, उपयोगकर्ता के बुद्धिमान व्यक्तिगत AI सहायक। ")
                    append("उपयोगकर्ता को 'Boss' कहो। ")
                    append("तुम्हारे पास उपयोगकर्ता की मोबाइल स्क्रीन पढ़ने की पूरी क्षमता है")
                    if (screenContext.isNotBlank()) {
                        append(" (स्क्रीन टेक्स्ट: \"${screenContext.take(500)}\")")
                    }
                    append("। कभी यह मत कहना कि 'मैं आपकी स्क्रीन नहीं देख सकता'। ")
                    append("कभी भी 'जी Boss मैं आपके साथ हूँ' बार-बार मत बोलना। ")
                    append("हमेशा 1 से 3 वाक्यों में सीधा और सटीक उत्तर दो।")
                }
            } else {
                buildString {
                    append("তুমি JARVIS (জার্ভিস), ব্যবহারকারীর অত্যন্ত বুদ্ধিমান ব্যক্তিগত AI সহকারী। ")
                    append("ব্যবহারকারীকে 'Boss' বলে সম্বোধন করবে। ")
                    append("তোমার কাছে ব্যবহারকারীর মোবাইলের স্ক্রিন দেখার ও পড়ার সম্পূর্ণ ক্ষমতা আছে")
                    if (screenContext.isNotBlank()) {
                        append(" (বর্তমান স্ক্রিনের লেখা: \"${screenContext.take(500)}\")")
                    }
                    append("। কখনোই বলবে না 'আমি স্ক্রিন দেখতে পারি না'। ")
                    append("অহেতুক 'জি Boss আমি আপনার সাথেই আছি' বারবার বলবে না। ")
                    append("সরাসরি, সঠিক ও সাবলীল বাংলায় ১-৩ বাক্যে উত্তর দাও। ")
                    append("ব্যবহারকারী যা বলেছে সেটা repeat করবে না।")
                }
            }

            // Build messages array
            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })

            // Add recent chat history (last 6 exchanges)
            val recentHistory = chatHistory.takeLast(12)
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

            // Try primary: Pollinations API
            var response = tryPollinationsApi(messages)

            // Fallback: Pollinations GET endpoint
            if (response == null) {
                response = tryPollinationsGet(systemPrompt, userInput)
            }

            if (response != null) {
                // Update history
                chatHistory.add("user" to userInput)
                chatHistory.add("assistant" to response)
                // Keep history manageable
                while (chatHistory.size > 20) {
                    chatHistory.removeAt(0)
                }
            }

            response ?: ""
        }
    }

    private fun tryPollinationsApi(messages: JSONArray): String? {
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
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                val choices = json.optJSONArray("choices")
                val content = choices?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                content?.trim()?.ifEmpty { null }
            } else {
                Log.e(TAG, "Pollinations API error: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pollinations API exception", e)
            null
        }
    }

    private fun tryPollinationsGet(systemPrompt: String, userInput: String): String? {
        return try {
            val query = java.net.URLEncoder.encode(
                "$systemPrompt\nপ্রশ্ন: $userInput", "UTF-8"
            )
            val request = Request.Builder()
                .url("https://text.pollinations.ai/$query")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.trim()?.ifEmpty { null }
            } else null
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
