package com.example.battleship

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

data class PlayerUi(
    val id: Int, val name: String, val color: Int,
    val alive: Boolean, val connected: Boolean, val ready: Boolean,
    val hits: Int, val sunk: Int, val shipsLeft: Int,
)

data class ShotUi(val x: Int, val y: Int, val hit: Boolean, val by: Int)

/** Tüm oyun arayüz durumu + ağ bağlantısı. */
class BattleState {
    private var server: GameServer? = null
    private var client: GameClient? = null

    var connected by mutableStateOf(false)     // istemci bağlı mı
    var connecting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    var myId by mutableStateOf(-1)
    var isHost by mutableStateOf(false)
    var hostAddress by mutableStateOf<String?>(null)   // QR için IP:port

    var phase by mutableStateOf("")
    var ships by mutableStateOf(4)
    var turnSec by mutableStateOf(25)
    var w by mutableStateOf(10)
    var h by mutableStateOf(10)

    var myShips by mutableStateOf<List<List<Pair<Int, Int>>>>(emptyList())
    var players by mutableStateOf<List<PlayerUi>>(emptyList())
    var shots by mutableStateOf<List<ShotUi>>(emptyList())
    var turn by mutableStateOf(-1)
    var deadline by mutableStateOf(0L)
    var log by mutableStateOf<List<String>>(emptyList())
    var winner by mutableStateOf(-1)

    val myReady: Boolean get() = players.firstOrNull { it.id == myId }?.ready == true
    val myTurn: Boolean get() = phase == "PLAYING" && turn == myId
    val amAlive: Boolean get() = players.firstOrNull { it.id == myId }?.alive != false

    // ---- Başlatma ----
    fun host(name: String) {
        reset()
        connecting = true
        val srv = GameServer()
        val port = srv.start()
        if (port < 0) { error = "Sunucu başlatılamadı."; connecting = false; return }
        server = srv
        val ip = NetUtil.localIpv4()
        hostAddress = if (ip != null) "$ip:$port" else null
        connectClient("127.0.0.1", port, name)
    }

    fun join(name: String, ip: String, port: Int) {
        reset()
        connecting = true
        connectClient(ip, port, name)
    }

    private fun connectClient(ip: String, port: Int, name: String) {
        val c = GameClient(
            onMessage = { onMsg(it) },
            onClosed = { reason ->
                connecting = false
                if (phase != "OVER") error = reason ?: "Bağlantı kesildi"
                connected = false
            },
        )
        client = c
        c.connect(ip, port)
        // bağlanınca join gönder
        c.send(JSONObject().put("t", "join").put("name", name))
        connected = true
        connecting = false
    }

    private fun onMsg(m: JSONObject) {
        when (m.optString("t")) {
            "welcome" -> {
                myId = m.optInt("id"); isHost = m.optBoolean("host")
            }
            "error" -> error = m.optString("msg")
            "yourships" -> {
                w = m.optInt("w"); h = m.optInt("h")
                val arr = m.optJSONArray("ships")
                val list = ArrayList<List<Pair<Int, Int>>>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val cells = arr.getJSONArray(i)
                    val cl = ArrayList<Pair<Int, Int>>()
                    for (j in 0 until cells.length()) {
                        val c = cells.getJSONArray(j)
                        cl.add(c.getInt(0) to c.getInt(1))
                    }
                    list.add(cl)
                }
                myShips = list
            }
            "state" -> {
                phase = m.optString("phase")
                ships = m.optInt("ships", ships)
                turnSec = m.optInt("turnSec", turnSec)
                w = m.optInt("w", w); h = m.optInt("h", h)
                turn = m.optInt("turn", -1)
                deadline = m.optLong("deadline", 0L)
                winner = m.optInt("winner", -1)
                val pj = m.optJSONArray("players")
                val pl = ArrayList<PlayerUi>()
                if (pj != null) for (i in 0 until pj.length()) {
                    val o = pj.getJSONObject(i)
                    pl.add(
                        PlayerUi(
                            o.optInt("id"), o.optString("name"), o.optInt("color"),
                            o.optBoolean("alive", true), o.optBoolean("connected", true),
                            o.optBoolean("ready", false),
                            o.optInt("hits"), o.optInt("sunk"), o.optInt("shipsLeft"),
                        )
                    )
                }
                players = pl
                val sj = m.optJSONArray("shots")
                val sl = ArrayList<ShotUi>()
                if (sj != null) for (i in 0 until sj.length()) {
                    val o = sj.getJSONObject(i)
                    sl.add(ShotUi(o.optInt("x"), o.optInt("y"), o.optBoolean("hit"), o.optInt("by")))
                }
                shots = sl
                val lj = m.optJSONArray("log")
                val ll = ArrayList<String>()
                if (lj != null) for (i in 0 until lj.length()) ll.add(lj.optString(i))
                log = ll
            }
        }
    }

    fun setConfig(newShips: Int, newTurn: Int) {
        client?.send(JSONObject().put("t", "config").put("ships", newShips).put("turnSec", newTurn))
    }

    fun startGame() { client?.send(JSONObject().put("t", "start")) }
    fun confirmReady() { client?.send(JSONObject().put("t", "ready")) }
    fun fire(x: Int, y: Int) { client?.send(JSONObject().put("t", "fire").put("x", x).put("y", y)) }

    fun leave() { reset() }

    fun reset() {
        try { client?.close() } catch (e: Exception) {}
        try { server?.stop() } catch (e: Exception) {}
        client = null; server = null
        connected = false; connecting = false; error = null
        myId = -1; isHost = false; hostAddress = null
        phase = ""; myShips = emptyList(); players = emptyList(); shots = emptyList()
        turn = -1; deadline = 0; log = emptyList(); winner = -1
    }
}
