package com.example.engine.model

import android.util.Log
import com.example.BuildConfig
import com.example.engine.VectorSimilarityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiEmbeddingService {

    companion object {
        private const val TAG = "GeminiEmbeddingService"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        const val EMBEDDING_MODEL = "gemini-embedding-2-preview"
        const val FLASH_MODEL = "gemini-3.5-flash"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun isApiKeyAvailable(): Boolean {
        val key = getApiKey()
        return key.isNotBlank() && key != "MY_GEMINI_API_KEY" && !key.startsWith("YOUR_")
    }

    private fun getApiKey(): String {
        return try {
            BuildConfig.GEMINI_API_KEY.trim()
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Embeds a single text string using gemini-embedding-2-preview.
     * Returns a 768-dimensional L2-normalized FloatArray.
     */
    suspend fun embedText(
        text: String,
        isQuery: Boolean = false,
        outputDimension: Int = 768
    ): FloatArray? = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (!isApiKeyAvailable()) {
            Log.d(TAG, "Gemini API key is not configured.")
            return@withContext null
        }

        try {
            val taskType = if (isQuery) "RETRIEVAL_QUERY" else "RETRIEVAL_DOCUMENT"
            val jsonPayload = JSONObject().apply {
                put("model", "models/$EMBEDDING_MODEL")
                put("content", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", text.take(8000))
                        })
                    })
                })
                put("taskType", taskType)
                if (outputDimension in listOf(384, 512, 768, 1536)) {
                    put("outputDimensionality", outputDimension)
                }
            }

            val url = "$BASE_URL$EMBEDDING_MODEL:embedContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                Log.w(TAG, "embedContent failed with code ${response.code}: $responseBody")
                return@withContext null
            }

            val json = JSONObject(responseBody)
            val embeddingObj = json.optJSONObject("embedding") ?: return@withContext null
            val valuesArray = embeddingObj.optJSONArray("values") ?: return@withContext null

            val result = FloatArray(valuesArray.length())
            for (i in 0 until valuesArray.length()) {
                result[i] = valuesArray.getDouble(i).toFloat()
            }

            VectorSimilarityUtils.l2Normalize(result)
        } catch (e: Exception) {
            Log.w(TAG, "Error calling Gemini embedding API: ${e.message}")
            null
        }
    }

    /**
     * Embeds a batch of texts using gemini-embedding-2-preview batchEmbedContents endpoint.
     */
    suspend fun embedBatch(
        texts: List<String>,
        isQuery: Boolean = false,
        outputDimension: Int = 768
    ): List<FloatArray>? = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val apiKey = getApiKey()
        if (!isApiKeyAvailable()) {
            return@withContext null
        }

        try {
            val taskType = if (isQuery) "RETRIEVAL_QUERY" else "RETRIEVAL_DOCUMENT"
            val requestsArray = JSONArray()

            for (text in texts) {
                val req = JSONObject().apply {
                    put("model", "models/$EMBEDDING_MODEL")
                    put("content", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", text.take(8000))
                            })
                        })
                    })
                    put("taskType", taskType)
                    if (outputDimension in listOf(384, 512, 768, 1536)) {
                        put("outputDimensionality", outputDimension)
                    }
                }
                requestsArray.put(req)
            }

            val rootPayload = JSONObject().apply {
                put("requests", requestsArray)
            }

            val url = "$BASE_URL$EMBEDDING_MODEL:batchEmbedContents?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(rootPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                Log.w(TAG, "batchEmbedContents failed with code ${response.code}: $responseBody")
                return@withContext null
            }

            val json = JSONObject(responseBody)
            val embeddingsArray = json.optJSONArray("embeddings") ?: return@withContext null

            val results = ArrayList<FloatArray>(embeddingsArray.length())
            for (i in 0 until embeddingsArray.length()) {
                val embObj = embeddingsArray.getJSONObject(i)
                val values = embObj.optJSONArray("values")
                if (values != null) {
                    val vec = FloatArray(values.length())
                    for (j in 0 until values.length()) {
                        vec[j] = values.getDouble(j).toFloat()
                    }
                    results.add(VectorSimilarityUtils.l2Normalize(vec))
                }
            }

            if (results.size == texts.size) results else null
        } catch (e: Exception) {
            Log.w(TAG, "Error in batchEmbedContents: ${e.message}")
            null
        }
    }

    data class EnrichedMetadata(
        val summary: String,
        val tags: List<String>,
        val keywords: List<String>
    )

    /**
     * Extracts rich semantic summary, tags, and conceptual keywords using gemini-3.5-flash
     * during document indexing.
     */
    suspend fun enrichDocumentContent(
        fileName: String,
        sampleContent: String
    ): EnrichedMetadata? = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (!isApiKeyAvailable()) return@withContext null

        try {
            val prompt = """
                Analyze the following document and extract metadata in strict JSON format.
                Document Name: $fileName
                Content:
                ${sampleContent.take(3000)}
                
                Respond ONLY with a valid JSON object matching:
                {
                  "summary": "1-2 sentence core concept summary",
                  "tags": ["3-5 short category tags, e.g. AI, Physics, Database, Android"],
                  "keywords": ["5-8 key technical terms, synonyms, or search phrases"]
                }
            """.trimIndent()

            val jsonPayload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.2)
                })
            }

            val url = "$BASE_URL$FLASH_MODEL:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                return@withContext null
            }

            val rootJson = JSONObject(responseBody)
            val candidates = rootJson.optJSONArray("candidates") ?: return@withContext null
            val firstCandidate = candidates.optJSONObject(0) ?: return@withContext null
            val content = firstCandidate.optJSONObject("content") ?: return@withContext null
            val parts = content.optJSONArray("parts") ?: return@withContext null
            val text = parts.optJSONObject(0)?.optString("text") ?: return@withContext null

            val parsed = JSONObject(text)
            val summary = parsed.optString("summary", "")
            val tagsArray = parsed.optJSONArray("tags")
            val tags = mutableListOf<String>()
            if (tagsArray != null) {
                for (i in 0 until tagsArray.length()) {
                    tags.add(tagsArray.getString(i).trim())
                }
            }
            val keywordsArray = parsed.optJSONArray("keywords")
            val keywords = mutableListOf<String>()
            if (keywordsArray != null) {
                for (i in 0 until keywordsArray.length()) {
                    keywords.add(keywordsArray.getString(i).trim())
                }
            }

            EnrichedMetadata(summary = summary, tags = tags, keywords = keywords)
        } catch (e: Exception) {
            Log.w(TAG, "Error in enrichDocumentContent: ${e.message}")
            null
        }
    }

    /**
     * Generates semantic query expansions using gemini-3.5-flash for richer search recall.
     */
    suspend fun expandQuery(query: String): List<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (!isApiKeyAvailable() || query.isBlank()) return@withContext emptyList()

        try {
            val prompt = """
                For the search query: "$query", generate 3-5 alternative search phrases, synonyms, and related technical concepts.
                Respond with a raw JSON array of strings, e.g. ["phrase1", "phrase2", "phrase3"]
            """.trimIndent()

            val jsonPayload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.3)
                })
            }

            val url = "$BASE_URL$FLASH_MODEL:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext emptyList()
            val rootJson = JSONObject(responseBody)
            val text = rootJson.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text") ?: return@withContext emptyList()

            val jsonArray = JSONArray(text)
            val result = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                result.add(jsonArray.getString(i))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }
}
