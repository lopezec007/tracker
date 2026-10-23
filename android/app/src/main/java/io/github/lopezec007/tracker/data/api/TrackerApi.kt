// The server's JSON API over OkHttp. Every network request of the app goes
// through here, to the one base URL the user configured (README: "Network
// policy"). Network failures surface as IOException, HTTP errors as
// ApiException; callers decide what is retryable.
package io.github.lopezec007.tracker.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An HTTP error answer: [code] and the server's "error" message. */
class ApiException(val code: Int, message: String) : Exception(message) {
    /** 4xx: the request itself is wrong and will not succeed on retry. */
    val isClientError get() = code in 400..499
}

class TrackerApi(val baseUrl: HttpUrl, client: OkHttpClient? = null) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** A client with short timeouts for the reachability probe. */
    private val probeClient: OkHttpClient by lazy {
        this.client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        /** The largest batch POST /fixes accepts. */
        const val MAX_BATCH = 500

        /** What the server tags entities created by this app with. */
        const val SOURCE_PHONE = "phone"

        /**
         * Builds the base URL from what the user typed: "10.0.0.2:8080",
         * "http://tracker.lan/", "tracker:8080". Returns null when it cannot
         * be parsed. Only http and https are accepted.
         */
        fun parseBaseUrl(text: String): HttpUrl? {
            var s = text.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "http://$s"
            val url = s.toHttpUrlOrNull() ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            return url.newBuilder().addPathSegment("").build() // trailing slash
        }
    }

    private fun url(vararg segments: String): HttpUrl {
        val b = baseUrl.newBuilder().addPathSegment("api")
        for (s in segments) b.addPathSegment(s)
        return b.build()
    }

    private fun jsonBody(text: String): RequestBody = text.toRequestBody(jsonType)

    private suspend fun exec(req: Request, client: OkHttpClient = this.client): Response = withContext(Dispatchers.IO) {
        val res = client.newCall(req).execute()
        if (!res.isSuccessful) {
            val body = try { res.body?.string().orEmpty() } catch (_: IOException) { "" }
            res.close()
            val msg = try {
                json.decodeFromString<ErrorDto>(body).error.ifBlank { "HTTP ${res.code}" }
            } catch (_: Exception) {
                "HTTP ${res.code}"
            }
            throw ApiException(res.code, msg)
        }
        res
    }

    private suspend inline fun <reified T> call(req: Request, client: OkHttpClient = this.client): T {
        val res = exec(req, client)
        return withContext(Dispatchers.IO) {
            res.use { r -> json.decodeFromString<T>(r.body?.string().orEmpty()) }
        }
    }

    // ---- reachability

    /** GET /api/version with short timeouts; throws IOException when the server cannot be seen. */
    suspend fun probe(): VersionDto = call(Request.Builder().url(url("version")).get().build(), probeClient)

    // ---- entities

    suspend fun listEntities(): List<EntityDto> = call(Request.Builder().url(url("entities")).get().build())

    suspend fun getEntity(id: String): EntityDto = call(Request.Builder().url(url("entities", id)).get().build())

    /** Creates or renames the entity (idempotent). */
    suspend fun putEntity(id: String, name: String, source: String = SOURCE_PHONE): EntityDto {
        val body = buildJsonObject { put("name", name); put("source", source) }.toString()
        return call(Request.Builder().url(url("entities", id)).put(jsonBody(body)).build())
    }

    // ---- fixes

    /** POST 1..[MAX_BATCH] fixes in order; the server takes all of them or none (400). */
    suspend fun postFixes(entityId: String, fixes: List<FixInput>): PostFixesDto {
        require(fixes.isNotEmpty() && fixes.size <= MAX_BATCH) { "a batch holds 1..$MAX_BATCH fixes" }
        val body = json.encodeToString(ListSerializer(FixInput.serializer()), fixes)
        return call(Request.Builder().url(url("entities", entityId, "fixes")).post(jsonBody(body)).build())
    }
}
