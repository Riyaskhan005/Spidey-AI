package com.riyas.SpideyAssistant

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class TavilySearchResult(
    val title: String,
    val url: String,
    val content: String,
)

class TavilyClient {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, apiKey: String): List<TavilySearchResult> =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "Add a Tavily API key in AI settings to search the web." }

            val body = JsonObject().apply {
                addProperty("api_key", apiKey)
                addProperty("query", query)
                addProperty("search_depth", "basic")
                addProperty("max_results", 5)
                addProperty("include_answer", false)
            }
            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Tavily search failed (${response.code}). Check the API key and try again.")
                }
                val responseBody = response.body?.string()
                    ?: throw IllegalStateException("Tavily returned an empty response.")
                val results = gson.fromJson(responseBody, JsonObject::class.java)
                    .getAsJsonArray("results")
                    ?: return@withContext emptyList()
                results.mapNotNull { element ->
                    val result = element.asJsonObject
                    val url = result.get("url")?.asString.orEmpty()
                    if (url.isBlank()) return@mapNotNull null
                    TavilySearchResult(
                        title = result.get("title")?.asString.orEmpty().ifBlank { url },
                        url = url,
                        content = result.get("content")?.asString.orEmpty(),
                    )
                }
            }
        }
}