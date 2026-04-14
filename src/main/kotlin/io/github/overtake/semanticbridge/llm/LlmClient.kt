package io.github.overtake.semanticbridge.llm

import io.github.overtake.semanticbridge.settings.PluginSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class LlmClient {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    fun stream(
        systemPrompt: String,
        userPrompt: String,
        isCancelled: () -> Boolean,
        onChunk: (String) -> Unit,
    ) {
        val settings = PluginSettings.getInstance()
        val selected = settings.selectedModel
        val apiKey = settings.activeApiKey
        if (apiKey.isBlank()) {
            throw LlmException.MissingApiKey(selected.provider.displayName)
        }

        val baseUrl = selected.provider.baseUrl.trimEnd('/')
        val model = selected.id

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.3)
            put("stream", true)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val response: HttpResponse<java.io.InputStream> = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: java.net.ConnectException) {
            throw LlmException.NetworkError("Could not connect to $baseUrl", e)
        } catch (e: java.net.http.HttpTimeoutException) {
            throw LlmException.NetworkError("Request timed out", e)
        } catch (e: Exception) {
            throw LlmException.NetworkError(e.message ?: "Unknown network error", e)
        }

        when (response.statusCode()) {
            200 -> parseSSEStream(response.body(), isCancelled, onChunk)
            401 -> throw LlmException.AuthenticationError()
            429 -> throw LlmException.RateLimitError()
            else -> {
                val errorBody = response.body().bufferedReader().use { it.readText() }
                throw LlmException.ApiError(response.statusCode(), errorBody)
            }
        }
    }

    private fun parseSSEStream(
        inputStream: java.io.InputStream,
        isCancelled: () -> Boolean,
        onChunk: (String) -> Unit,
    ) {
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (isCancelled()) {
                    inputStream.close()
                    return
                }

                val data = line ?: continue
                if (!data.startsWith("data: ")) continue

                val payload = data.removePrefix("data: ").trim()
                if (payload == "[DONE]") return

                try {
                    val json = JSONObject(payload)
                    val delta = json
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .optJSONObject("delta")
                        ?: continue
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        onChunk(content)
                    }
                } catch (_: Exception) {
                    // skip malformed SSE lines
                }
            }
        }
    }
}

sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MissingApiKey(provider: String = "selected provider") : LlmException("$provider API key not configured. Go to Settings > Tools > Semantic Bridge.")
    class AuthenticationError : LlmException("Invalid API key. Check your key in Settings > Tools > Semantic Bridge.")
    class RateLimitError : LlmException("Rate limit exceeded. Try again in a moment.")
    class NetworkError(detail: String, cause: Throwable? = null) : LlmException("Could not connect to the LLM API: $detail", cause)
    class ApiError(code: Int, body: String) : LlmException("API returned HTTP $code: ${body.take(200)}")
}
