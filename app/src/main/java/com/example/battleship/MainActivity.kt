package com.example.battleship

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.min

private val PLAYER_COLORS = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFDD835),
    Color(0xFF8E24AA), Color(0xFFFB8C00), Color(0xFF00ACC1), Color(0xFFD81B60),
)
private fun colorOf(i: Int) = PLAYER_COLORS[i % PLAYER_COLORS.size]

class MainActivity : ComponentActivity() {
    private val state = BattleState()

    override fun onDestroy() {
        super.onDestroy()
        state.reset()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF07192B)) {
                    App(state)
                }
            }
        }
    }
}

@Composable
private fun App(s: BattleState) {
    // Sistem geri tuşu: odadayken menüye dön (uygulamadan çıkma)
    BackHandler(enabled = s.connected || s.connecting) { s.leave() }
    when {
        !s.connected && !s.connecting -> HomeScreen(s)
        s.phase == "LOBBY" || s.phase == "" -> LobbyScreen(s)
        s.phase == "OVER" -> OverScreen(s)
        else -> GameScreen(s) // PLACING / PLAYING
    }
}

@Composable
private fun BackBar(label: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("← $label") }
        Spacer(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(s: BattleState) {
    var name by remember { mutableStateOf("") }
    var joining by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val txt = r.contents
        if (txt != null) joinFrom(s, name, txt)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("⚓ Amiral Battı", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Text("Aynı WiFi/hotspot üzerinde çok oyunculu", color = Color(0xFF9FB3C8))

        OutlinedTextField(
            value = name, onValueChange = { name = it.take(14) },
            label = { Text("Adın") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        s.error?.let { Text("❌ $it", color = MaterialTheme.colorScheme.error) }

        if (!joining) {
            Button(
                onClick = { if (name.isNotBlank()) s.host(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Oda Kur (Host)") }
            OutlinedButton(
                onClick = { if (name.isNotBlank()) joining = true },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Odaya Katıl") }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("🤖 Bilgisayara karşı (tek başına dene)", color = Color(0xFF9FB3C8))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3).forEach { n ->
                    OutlinedButton(
                        onClick = { s.hostVsBots(name.ifBlank { "Sen" }, n) },
                        modifier = Modifier.weight(1f),
                    ) { Text("$n bot") }
                }
            }
        } else {
            Button(
                onClick = {
                    scan.launch(
                        ScanOptions().setOrientationLocked(false)
                            .setPrompt("Host'un QR kodunu okut").setBeepEnabled(false)
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("📷 QR kodu okut") }

            Text("veya kodu elle gir (IP:port)", color = Color(0xFF9FB3C8))
            OutlinedTextField(
                value = manual, onValueChange = { manual = it },
                label = { Text("örn. 192.168.43.1:8899") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { if (manual.contains(":")) joinFrom(s, name, manual.trim()) },
                enabled = manual.contains(":"),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Bağlan") }
            TextButton(onClick = { joining = false }) { Text("Geri") }
        }
    }
}

private fun joinFrom(s: BattleState, name: String, text: String) {
    // text: "ip:port" (QR içeriği de aynı)
    val clean = text.substringAfter("//").trim()
    val idx = clean.lastIndexOf(":")
    if (idx <= 0) { s.error = "Geçersiz kod"; return }
    val ip = clean.substring(0, idx)
    val port = clean.substring(idx + 1).toIntOrNull() ?: return
    s.join(name.ifBlank { "Oyuncu" }, ip, port)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LobbyScreen(s: BattleState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BackBar("Menü") { s.leave() }
        Text("Lobi", style = MaterialTheme.typography.headlineSmall, color = Color.White)

        if (s.isHost && s.vsBots) {
            Text("Bilgisayara karşı mod", color = Color(0xFF9FB3C8))
            Text("Botlar katıldı, Başlat'a bas", color = Color(0xFF9FB3C8),
                style = MaterialTheme.typography.bodySmall)
        } else if (s.isHost) {
            val addr = s.hostAddress
            if (addr != null) {
                QrImage(addr)
                val clipboard = LocalClipboardManager.current
                var copied by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(addr, color = Color.White, fontFamily = FontFamily.Monospace)
                    FilledTonalButton(onClick = {
                        clipboard.setText(AnnotatedString(addr)); copied = true
                    }) { Text(if (copied) "✓ Kopyalandı" else "Kopyala") }
                }
                Text("Diğerleri bu QR'ı okutup katılsın", color = Color(0xFF9FB3C8),
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Ağ adresi bulunamadı. WiFi/hotspot açık mı?", color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text("Host'un başlatması bekleniyor…", color = Color(0xFF9FB3C8))
        }

        // Oyuncular
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Oyuncular (${s.players.size})", style = MaterialTheme.typography.titleMedium)
                s.players.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(colorOf(p.color)))
                        Text(p.name + if (p.id == s.myId) " (sen)" else "", color = Color.White)
                    }
                }
            }
        }

        if (s.isHost) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ayarlar", style = MaterialTheme.typography.titleMedium)
                    Text("Her oyuncunun gemi sayısı: ${s.ships}")
                    Slider(
                        value = s.ships.toFloat(), valueRange = 2f..6f, steps = 3,
                        onValueChange = { s.setConfig(it.toInt(), s.turnSec) },
                    )
                    Text("El süresi: ${s.turnSec} sn")
                    Slider(
                        value = s.turnSec.toFloat(), valueRange = 10f..60f, steps = 9,
                        onValueChange = { s.setConfig(s.ships, it.toInt()) },
                    )
                }
            }
            Button(
                onClick = { s.startGame() },
                enabled = s.players.size >= 2,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (s.players.size >= 2) "Başlat" else "En az 2 oyuncu gerekli") }
        }

        TextButton(onClick = { s.leave() }) { Text("Çık") }
    }
}

@Composable
private fun QrImage(text: String) {
    val bmp = remember(text) {
        try {
            BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 600, 600)
        } catch (e: Exception) { null }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(), contentDescription = "QR",
            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(10.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun GameScreen(s: BattleState) {
    val placing = s.phase == "PLACING"

    // Geri sayım
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(400) }
    }
    val remain = if (s.phase == "PLAYING" && s.deadline > 0) ((s.deadline - now) / 1000L).coerceAtLeast(0) else -1

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BackBar("Çık") { s.leave() }
        // Üst bilgi
        if (placing) {
            Text("Gemilerin yerleştirildi", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text("Haritada kendi gemilerini gör, onayla", color = Color(0xFF9FB3C8),
                style = MaterialTheme.typography.bodySmall)
        } else {
            val turnName = s.players.firstOrNull { it.id == s.turn }?.name ?: "-"
            val mine = s.myTurn
            Text(
                if (mine) "Sıra sende! Bir kareye ateş et" else "Sıra: $turnName",
                style = MaterialTheme.typography.titleMedium,
                color = if (mine) colorOf(s.players.firstOrNull { it.id == s.myId }?.color ?: 0) else Color.White,
            )
            if (remain >= 0) Text("⏱ $remain sn", color = Color(0xFF9FB3C8))
        }

        Board(s, enabled = s.myTurn && !placing) { x, y -> s.fire(x, y) }

        // Oyuncu durumları
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            s.players.forEach { p ->
                val isTurn = p.id == s.turn && !placing
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isTurn) colorOf(p.color).copy(alpha = 0.3f) else Color(0xFF0E2C49))
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(colorOf(p.color)))
                    Text(p.name, color = if (p.alive) Color.White else Color(0xFF6B7A8D),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center)
                    Text(if (!p.alive) "elendi" else "${p.shipsLeft} gemi",
                        color = Color(0xFF9FB3C8), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (placing) {
            Button(
                onClick = { s.confirmReady() },
                enabled = !s.myReady,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                val readyCount = s.players.count { it.ready }
                Text(if (s.myReady) "Onaylandı — diğerleri bekleniyor ($readyCount/${s.players.size})" else "Onayla ve Hazırım")
            }
        }

        // Olay akışı
        if (s.log.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    s.log.takeLast(5).forEach {
                        Text("• $it", color = Color(0xFFC8D6E5), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        TextButton(onClick = { s.leave() }) { Text("Oyundan çık") }
    }
}

@Composable
private fun Board(s: BattleState, enabled: Boolean, onFire: (Int, Int) -> Unit) {
    val w = s.w; val h = s.h
    val myCells = remember(s.myShips) { s.myShips.flatten().toHashSet() }
    val shotMap = remember(s.shots) { s.shots.associateBy { it.x to it.y } }
    val colorById = remember(s.players) { s.players.associate { it.id to colorOf(it.color) } }

    val water = Color(0xFF0B3C5D)
    val waterAlt = Color(0xFF0E456B)
    val gridLine = Color(0x33FFFFFF)
    val myShip = Color(0xFFB0BEC5)
    val myShipHit = Color(0xFF7F1D1D)
    val enemyHitBg = Color(0xFF241A1A)

    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF07263E))
            .pointerInput(enabled, w, h) {
                if (!enabled) return@pointerInput
                detectTapGestures { off ->
                    val cell = size.width.toFloat() / w
                    val x = floor(off.x / cell).toInt().coerceIn(0, w - 1)
                    val y = floor(off.y / cell).toInt().coerceIn(0, h - 1)
                    if (!shotMap.containsKey(x to y)) onFire(x, y)
                }
            },
    ) {
        val cell = min(size.width / w, size.height / h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val left = x * cell
                val top = y * cell
                val mine = myCells.contains(x to y)
                val shot = shotMap[x to y]
                val base = if ((x + y) % 2 == 0) water else waterAlt
                drawRect(base, Offset(left, top), Size(cell, cell))
                when {
                    shot != null && shot.hit && mine -> drawRect(myShipHit, Offset(left, top), Size(cell, cell))
                    shot != null && shot.hit -> drawRect(enemyHitBg, Offset(left, top), Size(cell, cell))
                    mine -> drawRect(myShip, Offset(left + cell * 0.12f, top + cell * 0.12f), Size(cell * 0.76f, cell * 0.76f))
                }
                if (shot != null) {
                    // İşaret, vuran oyuncunun renginde
                    val sc = colorById[shot.by] ?: Color.White
                    if (shot.hit) {
                        drawLine(sc, Offset(left + cell * 0.22f, top + cell * 0.22f),
                            Offset(left + cell * 0.78f, top + cell * 0.78f), cell * 0.14f)
                        drawLine(sc, Offset(left + cell * 0.78f, top + cell * 0.22f),
                            Offset(left + cell * 0.22f, top + cell * 0.78f), cell * 0.14f)
                    } else {
                        drawCircle(sc, cell * 0.14f, Offset(left + cell / 2, top + cell / 2))
                    }
                }
                drawRect(gridLine, Offset(left, top), Size(cell, cell), style = Stroke(1f))
            }
        }
    }
}

@Composable
private fun OverScreen(s: BattleState) {
    val winner = s.players.firstOrNull { it.id == s.winner }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("🏆", fontSize = 64.sp)
        Text(
            if (winner != null) "Kazanan: ${winner.name}" else "Oyun bitti",
            style = MaterialTheme.typography.headlineMedium, color = Color.White,
            textAlign = TextAlign.Center,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Skorlar", style = MaterialTheme.typography.titleMedium)
                s.players.sortedByDescending { it.sunk * 100 + it.hits }.forEach { p ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(colorOf(p.color)))
                            Text(p.name, color = Color.White)
                        }
                        Text("${p.sunk} batırma · ${p.hits} isabet", color = Color(0xFF9FB3C8))
                    }
                }
            }
        }
        Button(onClick = { s.leave() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Ana menüye dön")
        }
    }
}
