package com.example.battleship

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Oyunun yetkili (authoritative) sunucusu. Host cihazında çalışır; host dahil
 * herkes buraya istemci olarak bağlanır. Tüm oyun kuralları burada işler.
 */
class GameServer {

    private data class Ship(val cells: List<Pair<Int, Int>>) {
        val hit = BooleanArray(cells.size)
        val sunk: Boolean get() = hit.all { it }
    }

    private class SPlayer(val id: Int, val name: String, val color: Int) {
        var alive = true
        var ready = false
        var connected = true
        var hits = 0
        var sunkCount = 0
        var ships: List<Ship> = emptyList()
        var handler: ClientHandler? = null
    }

    enum class Phase { LOBBY, PLACING, PLAYING, OVER }

    private val game = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var timeoutTask: ScheduledFuture<*>? = null

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile var running = false; private set
    var port = 0; private set

    private val players = LinkedHashMap<Int, SPlayer>()
    private var nextId = 1
    private var hostId = -1

    private var phase = Phase.LOBBY
    private var shipsPerPlayer = 4
    private var turnSec = 25
    private var boardW = 10
    private var boardH = 10

    // Yapılan atışlar
    private data class Shot(val by: Int, val hit: Boolean)
    private val shots = LinkedHashMap<Pair<Int, Int>, Shot>()
    private val occupancy = HashMap<Pair<Int, Int>, Pair<Int, Int>>() // cell -> (playerId, shipIndex)
    private val log = ArrayList<String>()

    private var turnOrder = ArrayList<Int>()
    private var turnPos = 0
    private var deadline = 0L

    /** Sunucuyu başlatır; başarılıysa dinlenen portu döndürür, değilse -1. */
    fun start(preferredPort: Int = 8899): Int {
        for (p in preferredPort until preferredPort + 20) {
            try {
                val ss = ServerSocket(p)
                serverSocket = ss
                port = p
                running = true
                Thread { acceptLoop(ss) }.start()
                return p
            } catch (e: Exception) { /* bir sonraki portu dene */ }
        }
        return -1
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) {}
        game.shutdownNow(); timer.shutdownNow()
        synchronized(players) { players.values.forEach { it.handler?.close() } }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val sock = ss.accept()
                val h = ClientHandler(sock)
                Thread { h.run() }.start()
            } catch (e: Exception) {
                if (running) continue else break
            }
        }
    }

    // ---- İstemci bağlantısı ----
    inner class ClientHandler(private val socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val writer: BufferedWriter = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        private var playerId = -1

        fun run() {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val msg = try { JSONObject(line) } catch (e: Exception) { continue }
                    game.execute { handle(msg) }
                }
            } catch (e: Exception) {
            } finally {
                game.execute { onDisconnect() }
            }
        }

        fun send(obj: JSONObject) {
            try {
                synchronized(writer) {
                    writer.write(obj.toString()); writer.write("\n"); writer.flush()
                }
            } catch (e: Exception) { close() }
        }

        fun close() { try { socket.close() } catch (e: Exception) {} }

        private fun handle(msg: JSONObject) {
            when (msg.optString("t")) {
                "join" -> {
                    if (phase != Phase.LOBBY) { send(err("Oyun başladı, katılım kapalı.")); close(); return }
                    if (players.size >= 8) { send(err("Oda dolu (en fazla 8).")); close(); return }
                    val name = msg.optString("name").ifBlank { "Oyuncu" }
                    val id = nextId++
                    playerId = id
                    val color = (players.size) % 8
                    val sp = SPlayer(id, name, color)
                    sp.handler = this
                    players[id] = sp
                    if (hostId == -1) hostId = id
                    send(JSONObject().put("t", "welcome").put("id", id).put("host", id == hostId))
                    broadcastState()
                }
                "config" -> {
                    if (playerId != hostId || phase != Phase.LOBBY) return
                    shipsPerPlayer = msg.optInt("ships", shipsPerPlayer).coerceIn(2, 6)
                    turnSec = msg.optInt("turnSec", turnSec).coerceIn(10, 120)
                    broadcastState()
                }
                "start" -> {
                    if (playerId != hostId || phase != Phase.LOBBY) return
                    if (players.size < 2) { send(err("En az 2 oyuncu gerekli.")); return }
                    startGame()
                }
                "ready" -> {
                    if (phase != Phase.PLACING) return
                    players[playerId]?.ready = true
                    if (players.values.all { !it.connected || it.ready }) beginPlaying()
                    else broadcastState()
                }
                "fire" -> {
                    if (phase != Phase.PLAYING) return
                    if (currentTurnId() != playerId) return
                    fire(playerId, msg.optInt("x"), msg.optInt("y"))
                }
            }
        }

        private fun onDisconnect() {
            val sp = players[playerId] ?: return
            sp.connected = false
            log.add("${sp.name} ayrıldı")
            if (phase == Phase.LOBBY) {
                players.remove(playerId)
                if (playerId == hostId) hostId = players.keys.firstOrNull() ?: -1
            } else if (phase == Phase.PLAYING) {
                if (sp.alive) {
                    sp.alive = false
                    val wasTurn = currentTurnId() == playerId
                    turnOrder.remove(playerId)
                    if (turnPos >= turnOrder.size) turnPos = 0
                    if (!checkOver() && wasTurn) startTurnTimer()
                }
            }
            broadcastState()
        }
    }

    // ---- Oyun kurulum ----
    private fun startGame() {
        phase = Phase.PLACING
        val fleet = fleetFor(shipsPerPlayer)
        val n = players.size
        val totalCells = n * fleet.sum()
        var side = max(8, ceil(sqrt(totalCells / 0.28)).toInt())
        side = side.coerceAtMost(16)

        var placed: Map<Int, List<Ship>>? = null
        var trySide = side
        while (placed == null && trySide <= 22) {
            placed = tryPlaceAll(players.values.toList(), fleet, trySide)
            if (placed == null) trySide += 2
        }
        if (placed == null) { // olmadı, yine de en büyük tahtada zorla
            trySide = 22
            placed = tryPlaceAll(players.values.toList(), fleet, trySide) ?: emptyMap()
        }
        boardW = trySide; boardH = trySide
        occupancy.clear()
        placed.forEach { (pid, ships) ->
            players[pid]?.ships = ships
            ships.forEachIndexed { si, ship ->
                ship.cells.forEach { c -> occupancy[c] = pid to si }
            }
        }
        // Herkese kendi gemilerini gizli gönder, onay (hazır) bekle
        phase = Phase.PLACING
        players.values.forEach { sp -> sp.ready = false; sp.handler?.send(yourShipsMsg(sp)) }
        log.add("Gemiler yerleştirildi, oyuncular onaylıyor…")
        broadcastState()
    }

    private fun beginPlaying() {
        phase = Phase.PLAYING
        turnOrder = ArrayList(players.keys.filter { players[it]?.connected == true })
        turnPos = 0
        log.add("Oyun başladı! Sıra: ${players[currentTurnId()]?.name}")
        startTurnTimer()
        broadcastState()
    }

    private fun fleetFor(n: Int): List<Int> {
        val base = listOf(2, 3, 3, 4, 5, 4)
        return (0 until n).map { base[it % base.size] }
    }

    private fun tryPlaceAll(list: List<SPlayer>, fleet: List<Int>, side: Int): Map<Int, List<Ship>>? {
        val occ = HashSet<Pair<Int, Int>>()
        val result = HashMap<Int, List<Ship>>()
        for (sp in list) {
            val ships = ArrayList<Ship>()
            for (len in fleet.sortedDescending()) {
                var ok = false
                var attempt = 0
                while (attempt < 400 && !ok) {
                    attempt++
                    val horiz = Random.nextBoolean()
                    val x = Random.nextInt(side)
                    val y = Random.nextInt(side)
                    val cells = ArrayList<Pair<Int, Int>>()
                    var fits = true
                    for (i in 0 until len) {
                        val cx = if (horiz) x + i else x
                        val cy = if (horiz) y else y + i
                        if (cx >= side || cy >= side) { fits = false; break }
                        val c = cx to cy
                        if (occ.contains(c)) { fits = false; break }
                        cells.add(c)
                    }
                    if (fits) {
                        cells.forEach { occ.add(it) }
                        ships.add(Ship(cells))
                        ok = true
                    }
                }
                if (!ok) return null
            }
            result[sp.id] = ships
        }
        return result
    }

    // ---- Atış ----
    private fun fire(shooter: Int, x: Int, y: Int) {
        if (x < 0 || y < 0 || x >= boardW || y >= boardH) return
        val cell = x to y
        if (shots.containsKey(cell)) return // zaten atılmış

        val owner = occupancy[cell]
        val sp = players[shooter] ?: return
        if (owner != null) {
            shots[cell] = Shot(shooter, true)
            sp.hits++
            val (ownerId, shipIdx) = owner
            val ownerP = players[ownerId]
            val ship = ownerP?.ships?.getOrNull(shipIdx)
            if (ship != null) {
                val ci = ship.cells.indexOf(cell)
                if (ci >= 0) ship.hit[ci] = true
                if (ship.sunk) {
                    sp.sunkCount++
                    if (ownerId == shooter) log.add("${sp.name} kendi gemisini batırdı!")
                    else log.add("${sp.name}, ${ownerP.name} oyuncusunun gemisini batırdı")
                    // Sahibi tamamen bitti mi?
                    if (ownerP != null && ownerP.alive && ownerP.ships.all { it.sunk }) {
                        ownerP.alive = false
                        turnOrder.remove(ownerId)
                        log.add("${ownerP.name} elendi!")
                    }
                }
            }
            if (checkOver()) { broadcastState(); return }
            // İsabet → aynı oyuncu tekrar atar. Eleme sonrası konum kaymasına karşı yeniden hizala.
            val sidx = turnOrder.indexOf(shooter)
            turnPos = if (sidx >= 0) sidx else turnPos % turnOrder.size.coerceAtLeast(1)
            startTurnTimer()
        } else {
            shots[cell] = Shot(shooter, false)
            advanceTurn()
        }
        broadcastState()
    }

    private fun advanceTurn() {
        if (turnOrder.isEmpty()) return
        // şu anki oyuncunun turnOrder'daki konumundan sonrakine geç
        val cur = currentTurnId()
        val idx = turnOrder.indexOf(cur)
        turnPos = if (idx == -1) 0 else (idx + 1) % turnOrder.size
        startTurnTimer()
    }

    private fun currentTurnId(): Int {
        if (turnOrder.isEmpty()) return -1
        if (turnPos >= turnOrder.size) turnPos = 0
        return turnOrder[turnPos]
    }

    private fun checkOver(): Boolean {
        val alive = players.values.filter { it.alive }
        if (phase == Phase.PLAYING && alive.size <= 1) {
            phase = Phase.OVER
            timeoutTask?.cancel(false)
            val w = alive.firstOrNull()
            log.add(if (w != null) "🏆 Kazanan: ${w.name}" else "Oyun bitti")
            return true
        }
        return false
    }

    private fun startTurnTimer() {
        timeoutTask?.cancel(false)
        deadline = System.currentTimeMillis() + turnSec * 1000L
        timeoutTask = timer.schedule({
            game.execute { onTurnTimeout() }
        }, turnSec.toLong(), TimeUnit.SECONDS)
    }

    private fun onTurnTimeout() {
        if (phase != Phase.PLAYING) return
        val cur = currentTurnId()
        if (cur == -1) return
        // Süre doldu: sistem rastgele boş kareye atar
        val free = ArrayList<Pair<Int, Int>>()
        for (yy in 0 until boardH) for (xx in 0 until boardW) {
            val c = xx to yy
            if (!shots.containsKey(c)) free.add(c)
        }
        if (free.isEmpty()) { checkOver(); broadcastState(); return }
        val c = free[Random.nextInt(free.size)]
        log.add("${players[cur]?.name}: süre doldu, otomatik atış")
        fire(cur, c.first, c.second)
    }

    // ---- Mesaj üretimi ----
    private fun err(m: String) = JSONObject().put("t", "error").put("msg", m)

    private fun yourShipsMsg(sp: SPlayer): JSONObject {
        val arr = JSONArray()
        sp.ships.forEach { ship ->
            val cells = JSONArray()
            ship.cells.forEach { c -> cells.put(JSONArray().put(c.first).put(c.second)) }
            arr.put(cells)
        }
        return JSONObject().put("t", "yourships").put("w", boardW).put("h", boardH).put("ships", arr)
    }

    private fun stateMsg(): JSONObject {
        val pj = JSONArray()
        players.values.forEach { p ->
            pj.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("color", p.color)
                    .put("alive", p.alive).put("connected", p.connected)
                    .put("ready", p.ready)
                    .put("hits", p.hits).put("sunk", p.sunkCount)
                    .put("shipsLeft", p.ships.count { !it.sunk })
            )
        }
        val sj = JSONArray()
        shots.forEach { (cell, s) ->
            sj.put(JSONObject().put("x", cell.first).put("y", cell.second).put("hit", s.hit).put("by", s.by))
        }
        val lj = JSONArray()
        log.takeLast(8).forEach { lj.put(it) }
        return JSONObject()
            .put("t", "state")
            .put("phase", phase.name)
            .put("host", hostId)
            .put("ships", shipsPerPlayer)
            .put("turnSec", turnSec)
            .put("w", boardW).put("h", boardH)
            .put("turn", if (phase == Phase.PLAYING) currentTurnId() else -1)
            .put("deadline", deadline)
            .put("winner", if (phase == Phase.OVER) players.values.firstOrNull { it.alive }?.id ?: -1 else -1)
            .put("players", pj)
            .put("shots", sj)
            .put("log", lj)
    }

    private fun broadcastState() {
        val s = stateMsg()
        players.values.forEach { it.handler?.send(s) }
    }
}
