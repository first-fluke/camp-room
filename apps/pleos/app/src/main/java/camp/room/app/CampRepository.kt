package camp.room.app

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * Provider credentials and durable state remain on the server; this client never receives tokens.
 */
class CampRepository(private val baseUrl: String = BuildConfig.API_BASE_URL) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    suspend fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): JSONObject = suspendCancellableCoroutine { continuation ->
        val request =
            Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .header("Accept", "application/json")
                .method(
                    method,
                    body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()),
                )
                .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive)
                        continuation.resumeWith(
                            Result.failure(
                                IllegalStateException("서버와 연결되지 않았어요. 네트워크를 확인하고 다시 시도해 주세요.", e)
                            )
                        )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val text = response.body?.string().orEmpty()
                            val result =
                                runCatching { JSONObject(text) }
                                    .getOrElse {
                                        throw IllegalStateException("서버 응답을 읽지 못했어요. 다시 연결해 주세요.")
                                    }
                            if (!response.isSuccessful) {
                                val detail = result.opt("detail")
                                throw IllegalStateException(
                                    if (detail is String) detail
                                    else "입력한 조건을 다시 확인해 주세요. (HTTP ${response.code})"
                                )
                            }
                            if (continuation.isActive)
                                continuation.resumeWith(Result.success(result))
                        } catch (error: Exception) {
                            if (continuation.isActive)
                                continuation.resumeWith(Result.failure(error))
                        }
                    }
                }
            }
        )
    }

    suspend fun bootstrap() = Bootstrap.parse(request("/bootstrap"))

    suspend fun campsites(query: Map<String, String>) =
        request(
                "/campsites?" +
                    query.entries.joinToString("&") {
                        "${it.key}=${URLEncoder.encode(it.value,"UTF-8")}"
                    }
            )
            .objects("items")
            .map(Campsite::parse)
}
