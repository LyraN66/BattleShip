package com.example.battleship

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/** Oyun sunucusuna bağlanan istemci. Host dahil herkes bunu kullanır. */
class GameClient(
    private val onMessage: (JSONObject) -> Unit,
    private val onClosed: (String?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    @Volatile private var closed = false

    fun connect(ip: String, port: Int) {
        Thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 8000)
                socket = s
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val obj = try { JSONObject(line) } catch (e: Exception) { continue }
                    main.post { if (!closed) onMessage(obj) }
                }
                fail(null)
            } catch (e: Exception) {
                fail(e.message ?: "Bağlantı hatası")
            }
        }.start()
    }

    fun send(obj: JSONObject) {
        Thread {
            try {
                val w = writer ?: return@Thread
                synchronized(w) { w.write(obj.toString()); w.write("\n"); w.flush() }
            } catch (e: Exception) { fail(e.message) }
        }.start()
    }

    private fun fail(reason: String?) {
        if (closed) return
        main.post { if (!closed) { closed = true; onClosed(reason) } }
    }

    fun close() {
        closed = true
        try { socket?.close() } catch (e: Exception) {}
    }
}
