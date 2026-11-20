package yos.music.player.native

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 客户端适配 folia-major 使用的 @neteasecloudmusicapienhanced/api HTTP 接口。
 * 默认不写死公共服务地址；通过 NETEase_API_BASE（Gradle 属性或 Action secret）注入。
 */
object NeteaseApiService {
    private const val TAG = "NeteaseApiService"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean =
        yos.music.player.BuildConfig.NETEase_API_BASE.trim().isNotEmpty()

    private fun endpoint(path: String): String =
        "${yos.music.player.BuildConfig.NETEase_API_BASE.trimEnd('/')}/${
            path.trimStart('/')
        }"

    private fun get(path: String, cookie: String? = null): Pair<Int, JSONObject> {
        val url = endpoint(path).toHttpUrl().newBuilder()
            .addQueryParameter("timestamp", System.currentTimeMillis().toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse { JSONObject().put("body", body) }
            if (response.code !in 200..299) {
                throw IOException("网易云接口返回 HTTP ${response.code}")
            }
            response.headers("Set-Cookie").forEach { header ->
                val cookiePair = header.substringBefore(";")
                if (cookiePair.isNotBlank()) json.put("_netease_set_cookie", cookiePair)
            }
            return response.code to json
        }
    }

    fun loginQrKey(): Result<String> = runCatching {
        val (_, json) = get("/login/qr/key")
        val data = json.optJSONObject("data") ?: json
        data.optString("unikey", "")
            .ifEmpty { data.optString("qrcode", "") }
            .ifEmpty { throw IOException("网易云接口没有返回二维码 key") }
    }.onFailure { Log.e(TAG, "loginQrKey failed", it) }

    fun loginQrCreate(key: String): Result<String> = runCatching {
        val (_, json) = get("/login/qr/create?key=$key&qrimg=true")
        val data = json.optJSONObject("data") ?: json
        data.optString("base64", "")
            .ifEmpty { throw IOException("网易云接口没有返回二维码图片") }
    }.onFailure { Log.e(TAG, "loginQrCreate failed", it) }

    fun loginQrCheck(key: String): Result<Int> = runCatching {
        loginQrCheckDetailed(key).getOrThrow().status
    }.onFailure { Log.e(TAG, "loginQrCheck failed", it) }

    data class NeteaseQrCheck(val status: Int, val cookie: String?, val userId: String?)

    fun loginQrCheckDetailed(key: String): Result<NeteaseQrCheck> = runCatching {
        val (_, json) = get("/login/qr/check?key=$key")
        val data = json.optJSONObject("data") ?: json
        val status = data.optInt("code", data.optInt("status", -1))
        val jsonCookie = data.optString("cookie", "")
            .ifEmpty { json.optString("cookie", "") }
        val setCookie = json.optString("_netease_set_cookie", "")
        val cookie = jsonCookie.ifEmpty { setCookie }.ifBlank { null }
        val userId = cookie?.let { accountUserId(it) }
        NeteaseQrCheck(status, cookie, userId)
    }.onFailure { Log.e(TAG, "loginQrCheck failed", it) }

    fun accountUserId(cookie: String): String? = runCatching {
        val (_, json) = get("/user/account", cookie)
        val profile = json.optJSONObject("profile")
        profile?.optLong("userId", 0)?.takeIf { it > 0 }?.toString()
            ?: json.optJSONObject("account")?.optLong("id", 0)?.takeIf { it > 0 }?.toString()
    }.onFailure { Log.e(TAG, "user/account failed", it) }.getOrNull()
}
