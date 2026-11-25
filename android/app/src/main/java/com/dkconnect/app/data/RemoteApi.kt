package com.dkconnect.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DkFriend(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val status: String,
    val outgoing: Boolean,
    val online: Boolean
)

data class DkStory(val id: String, val userId: String, val name: String, val mediaUrl: String, val caption: String, val createdAt: String)

data class DkMessagePage(val messages: List<DkMessage>, val nextBefore: String?)

data class DkMessage(
    val id: String,
    val senderId: String,
    val receiverId: String,
    val body: String,
    val createdAt: String,
    val readAt: String?
)

class RemoteApi(private val token: String, private val baseUrl: String = ApiConfig.BASE_URL) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun request(path: String): Request.Builder = Request.Builder()
        .url(baseUrl.trimEnd('/') + "/" + path.trimStart('/'))
        .header("Authorization", "Bearer $token")

    private suspend fun execute(builder: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = try { JSONObject(raw.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
            if (!response.isSuccessful) {
                val message = json.optString("message").ifBlank { json.optString("error", "Yêu cầu thất bại (${response.code})") }
                throw IllegalStateException(message)
            }
            json
        }
    }

    suspend fun friends(): List<DkFriend> {
        val a = execute(request("api/friends").get()).optJSONArray("friends") ?: JSONArray()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            DkFriend(o.optString("id"), o.optString("display_name", "Người dùng"), o.optString("avatar_url").takeIf(String::isNotBlank), o.optString("status"), o.optBoolean("outgoing"), o.optBoolean("online"))
        }
    }

    suspend fun searchUsers(query: String): List<DkFriend> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val a = execute(request("api/users?q=$encoded").get()).optJSONArray("users") ?: JSONArray()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            DkFriend(o.optString("id"), o.optString("display_name", "Người dùng"), o.optString("avatar_url").takeIf(String::isNotBlank), "none", false, false)
        }
    }

    suspend fun requestFriend(id: String): String {
        val body = JSONObject().put("friendId", id).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        return execute(request("api/friends/request").post(body)).optString("status")
    }

    suspend fun acceptFriend(id: String) {
        val body = JSONObject().put("requesterId", id).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        execute(request("api/friends/accept").post(body))
    }

    suspend fun messagePage(peerId: String, before: String? = null, limit: Int = 100): DkMessagePage {
        val cursor = before?.let { "&before=$it" } ?: ""
        val json = execute(request("api/messages/$peerId?limit=$limit$cursor").get())
        val a = json.optJSONArray("messages") ?: JSONArray()
        val rows = (0 until a.length()).map { i -> a.getJSONObject(i).toMessage() }
        val rawCursor = json.opt("nextBefore")
        val next = if (rawCursor == null || rawCursor == JSONObject.NULL) null else rawCursor.toString()
        return DkMessagePage(rows, next)
    }

    suspend fun messages(peerId: String): List<DkMessage> = messagePage(peerId).messages

    suspend fun sendMessage(peerId: String, bodyText: String, clientId: String = java.util.UUID.randomUUID().toString()): DkMessage {
        val body = JSONObject().put("body", bodyText).put("clientId", clientId).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        return execute(request("api/messages/$peerId").post(body)).getJSONObject("message").toMessage()
    }

    suspend fun markRead(peerId: String) {
        execute(request("api/messages/$peerId/read").post("{}".toRequestBody("application/json; charset=utf-8".toMediaType())))
    }

    suspend fun recallMessage(messageId: String) {
        execute(request("api/messages/$messageId/recall").post("{}".toRequestBody("application/json; charset=utf-8".toMediaType())))
    }

    private suspend fun upload(context: Context, uri: Uri, path: String, caption: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val allowed = mime.startsWith("image/") || mime.startsWith("video/")
        require(allowed) { "Chỉ hỗ trợ ảnh hoặc video." }
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
        require(size <= 25L * 1024 * 1024) { "Tệp vượt quá 25 MB." }
        val extension = when (mime) { "image/png" -> ".png"; "image/webp" -> ".webp"; "video/quicktime" -> ".mov"; "video/webm" -> ".webm"; else -> if (mime.startsWith("video/")) ".mp4" else ".jpg" }
        val filename = "dkconnect_${System.currentTimeMillis()}$extension"
        val fileBody = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(64 * 1024); var total = 0L; var count = 0
                    while (input.read(buffer).also { count = it } != -1) {
                        total += count
                        if (total > 25L * 1024 * 1024) throw java.io.IOException("Tệp vượt quá 25 MB.")
                        sink.write(buffer, 0, count)
                    }
                } ?: throw java.io.IOException("Không đọc được tệp đã chọn.")
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .apply { if (caption != null) addFormDataPart("caption", caption) }
            .addFormDataPart("media", filename, fileBody).build()
        client.newCall(request(path).post(multipart).build()).execute().use { response ->
            val raw = response.body?.string().orEmpty(); val json = JSONObject(raw.ifBlank { "{}" })
            if (!response.isSuccessful) throw IllegalStateException(json.optString("error", "Tải media thất bại (${response.code})"))
            json
        }
    }

    suspend fun uploadSticker(context: Context, uri: Uri): String = upload(context, uri, "api/stickers").getJSONObject("sticker").optString("media_url")
    suspend fun uploadStory(context: Context, uri: Uri, caption: String = ""): DkStory {
        val o = upload(context, uri, "api/stories/upload", caption).getJSONObject("story")
        return DkStory(o.optString("id"), o.optString("user_id"), "Bạn", o.optString("media_url"), o.optString("caption"), o.optString("created_at"))
    }
    suspend fun stories(): List<DkStory> {
        val a = execute(request("api/stories").get()).optJSONArray("stories") ?: JSONArray()
        return (0 until a.length()).map { i -> val o = a.getJSONObject(i); DkStory(o.optString("id"), o.optString("user_id"), o.optString("display_name", "Bạn bè"), o.optString("media_url"), o.optString("caption"), o.optString("created_at")) }
    }

    private fun JSONObject.toMessage() = DkMessage(
        optString("id"), optString("sender_id"), optString("receiver_id"), optString("body"), optString("created_at"), optString("read_at").takeIf(String::isNotBlank)
    )
}
