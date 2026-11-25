package com.dkconnect.app.data

import io.socket.client.IO
import io.socket.client.Ack
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject

class RealtimeClient(baseUrl: String, token: String) {
    private val socket: Socket = IO.socket(baseUrl.trimEnd('/'), IO.Options.builder()
        .setAuth(mapOf("token" to token))
        .setReconnection(true)
        .setReconnectionAttempts(Int.MAX_VALUE)
        .setReconnectionDelay(1_000)
        .setReconnectionDelayMax(10_000)
        .build())

    fun connect() { if (!socket.connected()) socket.connect() }
    fun disconnect() { socket.disconnect(); socket.off() }
    fun off(event: String) { socket.off(event) }
    fun isConnected() = socket.connected()
    fun on(event: String, listener: (JSONObject) -> Unit): Emitter.Listener {
        val handler = Emitter.Listener { args ->
            val value = args.firstOrNull()
            if (value is JSONObject) listener(value)
        }
        socket.on(event, handler)
        return handler
    }
    fun off(event: String, listener: Emitter.Listener) { socket.off(event, listener) }
    fun send(peerId: String, body: String, clientId: String, callback: (Boolean, DkMessage?, String?) -> Unit) {
        val payload = JSONObject().put("receiverId", peerId).put("body", body).put("clientId", clientId)
        socket.emit("message:send", payload, Ack { args ->
            val result = args.firstOrNull() as? JSONObject
            val value = result?.optJSONObject("message")
            val message = value?.let { DkMessage(it.optString("id"), it.optString("sender_id"), it.optString("receiver_id"), it.optString("body"), it.optString("created_at"), it.optString("read_at").takeIf(String::isNotBlank)) }
            callback(result?.optBoolean("ok") == true, message, result?.optString("error"))
        })
    }
    fun typing(peerId: String, active: Boolean) { socket.emit(if (active) "typing:start" else "typing:stop", JSONObject().put("to", peerId)) }
}
