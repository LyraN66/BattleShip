package com.example.battleship

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** İnternet modu istemcisi: uzak sunucuya WebSocket ile bağlanır. */
class WsClient(
    private val onMessage: (JSONObject) -> Unit,
    private val onClosed: (String?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(45, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    @Volatile private var closed = false

    fun connect(url: String, hello: JSONObject?) {
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (hello != null) webSocket.send(hello.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = try { JSONObject(text) } catch (e: Exception) { return }
                main.post { if (!closed) onMessage(o) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(t.message ?: "Bağlantı hatası")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                fail(if (reason.isBlank()) "Bağlantı kapandı" else reason)
            }
        })
    }

    fun send(obj: JSONObject) { try { ws?.send(obj.toString()) } catch (e: Exception) {} }

    private fun fail(reason: String?) {
        if (closed) return
        main.post { if (!closed) { closed = true; onClosed(reason) } }
    }

    fun close() {
        closed = true
        try { ws?.close(1000, null) } catch (e: Exception) {}
    }
}
