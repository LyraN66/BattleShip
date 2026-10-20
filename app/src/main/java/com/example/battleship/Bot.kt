package com.example.battleship

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.random.Random

/**
 * Basit bilgisayar oyuncusu. Host ile aynı cihazda, 127.0.0.1 üzerinden sunucuya
 * istemci olarak bağlanır; yerleştirmeyi otomatik onaylar, sırası gelince ateş eder.
 * "Avla-vur" mantığı: bir isabet varsa çevresine nişan alır, yoksa rastgele.
 */
class Bot(private val name: String, private val ip: String, private val port: Int) {
    private val main = Handler(Looper.getMainLooper())
    private var id = -1
    private var w = 0
    private var h = 0
    private var phase = ""
    private var turn = -1
    private var readySent = false
    private var pendingFire = false
    private var lastFireShots = -1

    private val shotCells = HashSet<Pair<Int, Int>>()
    private val hitCells = ArrayList<Pair<Int, Int>>()

    private val client = GameClient(
        onMessage = { onMsg(it) },
        onClosed = { },
    )

    fun start() {
        client.connect(ip, port)
        client.send(JSONObject().put("t", "join").put("name", name))
    }

    fun stop() { client.close() }

    private fun onMsg(m: JSONObject) {
        when (m.optString("t")) {
            "welcome" -> id = m.optInt("id")
            "state" -> {
                phase = m.optString("phase")
                w = m.optInt("w", w); h = m.optInt("h", h)
                turn = m.optInt("turn", -1)
                shotCells.clear(); hitCells.clear()
                val sj = m.optJSONArray("shots")
                if (sj != null) for (i in 0 until sj.length()) {
                    val o = sj.getJSONObject(i)
                    val c = o.optInt("x") to o.optInt("y")
                    shotCells.add(c)
                    if (o.optBoolean("hit")) hitCells.add(c)
                }
                if (phase == "PLACING" && !readySent) {
                    readySent = true
                    client.send(JSONObject().put("t", "ready"))
                }
                if (phase == "PLAYING" && turn == id && !pendingFire && shotCells.size != lastFireShots) {
                    pendingFire = true
                    lastFireShots = shotCells.size
                    main.postDelayed({
                        pendingFire = false
                        if (phase == "PLAYING" && turn == id) doFire()
                    }, 700)
                }
            }
        }
    }

    private fun doFire() {
        val target = pickTarget() ?: return
        client.send(JSONObject().put("t", "fire").put("x", target.first).put("y", target.second))
    }

    private fun pickTarget(): Pair<Int, Int>? {
        if (w <= 0 || h <= 0) return null
        // İsabetlerin komşuları (avla-vur)
        val neighbors = ArrayList<Pair<Int, Int>>()
        val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        for (hc in hitCells) for ((dx, dy) in dirs) {
            val nx = hc.first + dx; val ny = hc.second + dy
            if (nx in 0 until w && ny in 0 until h && !shotCells.contains(nx to ny)) {
                neighbors.add(nx to ny)
            }
        }
        if (neighbors.isNotEmpty()) return neighbors[Random.nextInt(neighbors.size)]
        // Rastgele boş kare
        val free = ArrayList<Pair<Int, Int>>()
        for (y in 0 until h) for (x in 0 until w) {
            if (!shotCells.contains(x to y)) free.add(x to y)
        }
        if (free.isEmpty()) return null
        return free[Random.nextInt(free.size)]
    }
}
