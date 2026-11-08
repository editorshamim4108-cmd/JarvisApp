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

    var provider: String = "FREE"
    var apiKey: String = ""

    suspend fun ask(userInput: String, screenContext: String, locale: String): String {
        return withContext(Dispatchers.IO) {
            val isHindi = locale.startsWith("hi")
            val systemPrompt = buildSystemPrompt(isHindi, screenContext)

            var response: String? = null

            if (apiKey.isNotBlank() && provider != "FREE") {
                response = when (provider) {
                    "CLAUDE" -> tryClaude(systemPrompt, userInput)
                    "GROQ" -> tryOpenAiCompatible(
                        "https://api.groq.com/openai/v1/chat/completions",
                        apiKey, "llama-3.3-70b-versatile", systemPrompt, userInput
                    )
                    "GEMINI" -> tryGemini(systemPrompt, userInput)
                    "OPENROUTER" -> tryOpenAiCompatible(
                        "https://openrouter.ai/api/v1/chat/completions",
                        apiKey, "meta-llama/llama-3.1-8b-instruct:free", systemPrompt, userInput
                    )
                    else -> null
                }
            }

            if (response == null) {
                response = tryPollinations(systemPrompt, userInput)
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

    private fun tryClaude(systemPrompt: String, userInput: String): String? {
        return try {
            val messages = JSONArray()
            for ((role, content) in chatHistory.takeLast(8)) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
            messages.put(JSONObject().put("role", "user").put("content", userInput))

            val body = JSONObject().apply {
                put("model", "claude-haiku-4-0")
                put("max_tokens", 300)
                put("system", systemPrompt)
                put("messages", messages)
            }

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val resp = client.newCall(request).execute()
            val bodyStr = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                Log.e(TAG, "Claude error: ${resp.code} ${bodyStr.take(300)}")
                return null
            }

            JSONObject(bodyStr).optJSONArray("content")
                ?.optJSONObject(0)
                ?.optString("text", "")
                ?.trim()?.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Claude exception", e)
            null
        }
    }

    private fun tryOpenAiCompatible(
        url: String, key: String, model: String,
        systemPrompt: String, userInput: String
    ): String? {
        return try {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
            for ((role, content) in chatHistory.takeLast(8)) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
            messages.put(JSONObject().put("role", "user").put("content", userInput))

            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.7)
                put("max_tokens", 300)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val resp = client.newCall(request).execute()
            val bodyStr = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                Log.e(TAG, "OpenAI-compat error ($url): ${resp.code} ${bodyStr.take(300)}")
                return null
            }

            JSONObject(bodyStr).optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()?.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI-compat exception ($url)", e)
            null
        }
    }

    private fun tryGemini(systemPrompt: String, userInput: String): String? {
        return try {
            val parts = JSONArray()
            for ((role, content) in chatHistory.takeLast(8)) {
                parts.put(JSONObject().apply {
                    put("role", if (role == "assistant") "model" else "user")
                    put("parts", JSONArray().put(JSONObject().put("text", content)))
                })
            }
            parts.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userInput)))
            })

            val body = JSONObject().apply {
                put("system_instruction", JSONObject().put("parts",
                    JSONArray().put(JSONObject().put("text", systemPrompt))))
                put("contents", parts)
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 300)
                    put("temperature", 0.7)
                })
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val resp = client.newCall(request).execute()
            val bodyStr = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                Log.e(TAG, "Gemini error: ${resp.code} ${bodyStr.take(300)}")
                return null
            }

            JSONObject(bodyStr).optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", "")
                ?.trim()?.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini exception", e)
            null
        }
    }

    private fun tryPollinations(systemPrompt: String, userInput: String): String? {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for ((role, content) in chatHistory.takeLast(8)) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userInput))

        for (model in listOf("openai", "openai-fast")) {
            try {
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("temperature", 0.7)
                }

                val request = Request.Builder()
                    .url("https://text.pollinations.ai/openai")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val resp = client.newCall(request).execute()
                val bodyStr = resp.body?.string() ?: ""

                if (!resp.isSuccessful) {
                    Log.e(TAG, "Pollinations error ($model): ${resp.code}")
                    continue
                }

                if (bodyStr.contains("reached its budget", ignoreCase = true) ||
                    bodyStr.contains("rate limit", ignoreCase = true)) {
                    Log.w(TAG, "Pollinations budget/rate error ($model)")
                    continue
                }

                val content = JSONObject(bodyStr).optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()?.ifEmpty { null }

                if (content != null && !content.contains("reached its budget", ignoreCase = true)) {
                    return content
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pollinations exception ($model)", e)
            }
        }
        return null
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

    fun clearHistory() {
        chatHistory.clear()
    }

    companion object {
        private const val TAG = "AiBrain"
    }
}
