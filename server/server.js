// Amiral Battı - internet (WebSocket) oyun sunucusu
// Oda kodu ile buluşma + tüm oyun kuralları. Birden çok oda aynı anda çalışır.
const http = require("http");
const fs = require("fs");
const path = require("path");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 8080;
let PAGE = "";
try { PAGE = fs.readFileSync(path.join(__dirname, "public", "index.html")); } catch (e) {}

// ---- Yardımcılar ----
const key = (x, y) => `${x},${y}`;
const rnd = (n) => Math.floor(Math.random() * n);

// Tetris benzeri gemi şekilleri (bağıl hücreler). Her gemiye bir şekil atanır.
const SHAPE_POOL = [
  [[0,0],[1,0]],                   // I2 (düz-2)
  [[0,0],[1,0],[2,0]],             // I3 (düz-3)
  [[0,0],[0,1],[1,1]],             // L3 (köşe)
  [[0,0],[1,0],[0,1],[1,1]],       // O (2x2 kare)
  [[0,0],[1,0],[2,0],[1,1]],       // T4
  [[0,0],[1,0],[1,1],[2,1]],       // S4 (zikzak)
  [[0,0],[1,0],[2,0],[3,0]],       // I4 (düz-4)
  [[0,0],[0,1],[0,2],[1,2]],       // L4
];
function fleetShapes(n) {
  return SHAPE_POOL.slice(0, Math.min(Math.max(2, n), SHAPE_POOL.length)).map((s) => s.map((c) => [c[0], c[1]]));
}
// Şekli rastgele döndür + aynala, sonra (0,0) köşesine normalize et
function transformShape(cells) {
  let c = cells.map(([x, y]) => [x, y]);
  const rot = Math.floor(Math.random() * 4);
  for (let r = 0; r < rot; r++) c = c.map(([x, y]) => [y, -x]);
  if (Math.random() < 0.5) c = c.map(([x, y]) => [-x, y]);
  const minx = Math.min(...c.map((p) => p[0])), miny = Math.min(...c.map((p) => p[1]));
  return c.map(([x, y]) => [x - minx, y - miny]);
}

const rooms = new Map(); // code -> room

function makeCode() {
  const digits = "0123456789";
  let code;
  do {
    code = "";
    for (let i = 0; i < 4; i++) code += digits[rnd(digits.length)];
  } while (rooms.has(code));
  return code;
}

function makeToken() {
  return Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2);
}

function newRoom(code) {
  return {
    code,
    players: new Map(), // id -> player
    nextId: 1,
    hostId: -1,
    phase: "LOBBY",
    shipsPerPlayer: 4,
    turnSec: 25,
    w: 10, h: 10,
    shots: new Map(), // key -> {by, hit}
    occupancy: new Map(), // key -> [pid, shipIdx]
    log: [],
    turnOrder: [],
    turnPos: 0,
    deadline: 0,
    sunkSeq: 0, sunkBy: "", sunkOwner: "", sunkCells: null, sunkByChar: 0,
    epoch: 0,                 // bir oyuncunun kesintisiz sırası (ulti/solo tespiti için)
    hitInfo: new Map(),       // key -> {by, epoch}
    ultiPlayer: -1, ultiLeft: 0, ultiSeq: 0, ultiBy: "",
    mines: new Set(),         // gizli hazine hücreleri (bulan +2 atışlık ulti)
    timer: null,
  };
}

function send(ws, obj) {
  try { if (ws.readyState === 1) ws.send(JSON.stringify(obj)); } catch (e) {}
}

function broadcast(room) {
  const s = stateMsg(room);
  for (const p of room.players.values()) if (p.ws) send(p.ws, s);
}

function currentTurnId(room) {
  if (room.turnOrder.length === 0) return -1;
  if (room.turnPos >= room.turnOrder.length) room.turnPos = 0;
  return room.turnOrder[room.turnPos];
}

// ---- Yerleştirme ----
function tryPlaceAll(room, players, fleet, side) {
  const occ = new Set();
  const result = new Map();
  for (const p of players) {
    const ships = [];
    // büyük şekilleri önce yerleştir (daha kolay sığar)
    const order = [...fleet].sort((a, b) => b.length - a.length);
    for (const shape of order) {
      let ok = false, attempt = 0;
      while (attempt < 800 && !ok) {
        attempt++;
        const t = transformShape(shape);
        const sw = Math.max(...t.map((c) => c[0])) + 1;
        const sh = Math.max(...t.map((c) => c[1])) + 1;
        if (sw > side || sh > side) continue;
        const ox = rnd(side - sw + 1), oy = rnd(side - sh + 1);
        const cells = t.map(([x, y]) => [x + ox, y + oy]);
        if (cells.every((c) => !occ.has(key(c[0], c[1])))) {
          cells.forEach((c) => occ.add(key(c[0], c[1])));
          ships.push({ cells, hit: cells.map(() => false) });
          ok = true;
        }
      }
      if (!ok) return null;
    }
    result.set(p.id, ships);
  }
  return result;
}

function shipSunk(ship) { return ship.hit.every((h) => h); }

// ---- Bot (test/GM modu) ----
const BOT_NAMES = ["Komodor Bot", "Amiral Bot", "Reis Bot", "Kaptan Bot"];
function addBot(room) {
  const id = room.nextId++;
  const color = room.players.size % 8;
  const p = {
    id, name: BOT_NAMES[rnd(BOT_NAMES.length)], color, alive: true, ready: false,
    connected: true, hits: 0, sunkCount: 0, ships: [], voiceOn: false,
    wins: 0, tSunk: 0, tHits: 0, games: 0, score: 0, ultiScoreTier: 0, tScore: 0, char: rnd(6),
    ws: null, token: null, graceTimer: null, bot: true,
  };
  room.players.set(id, p);
  return p;
}
function botPick(room, botId) {
  const W = room.w, H = room.h;
  const shot = (x, y) => room.shots.has(key(x, y));
  // Hedef modu: batmamış bir geminin vurulmuş hücresinin komşularını dene
  const targets = [];
  for (const [k, s] of room.shots) {
    if (!s.hit) continue;
    const occ = room.occupancy.get(k);
    if (!occ) continue;
    const [oid, si] = occ;
    if (oid === botId) continue;
    const op = room.players.get(oid);
    const ship = op && op.ships ? op.ships[si] : null;
    if (!ship || shipSunk(ship)) continue;
    const [x, y] = k.split(",").map(Number);
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const nx = x + dx, ny = y + dy;
      if (nx >= 0 && ny >= 0 && nx < W && ny < H && !shot(nx, ny)) targets.push([nx, ny]);
    }
  }
  if (targets.length) return targets[rnd(targets.length)];
  // Avlanma modu: boş hücrelerden rastgele
  const free = [];
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (!shot(x, y)) free.push([x, y]);
  return free.length ? free[rnd(free.length)] : null;
}
function scheduleBot(room) {
  if (room.phase !== "PLAYING") return;
  const cur = currentTurnId(room);
  const p = room.players.get(cur);
  if (!p || !p.bot) return;
  if (room.botTimer) return;
  room.botTimer = setTimeout(() => {
    room.botTimer = null;
    if (room.phase !== "PLAYING") return;
    if (currentTurnId(room) !== cur) return;
    const t = botPick(room, cur);
    if (t) fire(room, cur, t[0], t[1]);
  }, 650 + rnd(650));
}
function clearBot(room) { if (room.botTimer) { clearTimeout(room.botTimer); room.botTimer = null; } }
function sendGmShips(room) {
  if (!room.gm) return;
  const host = room.players.get(room.hostId);
  if (!host || !host.ws) return;
  const list = [];
  for (const pp of room.players.values()) {
    if (pp.id === room.hostId) continue;
    for (const ship of (pp.ships || [])) list.push({ owner: pp.id, color: pp.color, cells: ship.cells });
  }
  send(host.ws, { t: "gmships", ships: list });
}

function startGame(room) {
  const players = [...room.players.values()];
  const fleet = fleetShapes(room.shipsPerPlayer);
  const total = players.length * fleet.reduce((a, s) => a + s.length, 0);
  let side = Math.max(9, Math.ceil(Math.sqrt(total / 0.24)));
  side = Math.min(side, 17);

  let placed = null, trySide = side;
  while (placed === null && trySide <= 24) {
    placed = tryPlaceAll(room, players, fleet, trySide);
    if (placed === null) trySide += 2;
  }
  if (placed === null) { trySide = 24; placed = tryPlaceAll(room, players, fleet, 24) || new Map(); }
  room.w = trySide; room.h = trySide;
  room.occupancy.clear();
  for (const [pid, ships] of placed.entries()) {
    const p = room.players.get(pid);
    if (p) p.ships = ships;
    ships.forEach((ship, si) => ship.cells.forEach((c) => room.occupancy.set(key(c[0], c[1]), [pid, si])));
  }
  // Gizli hazine: 2 boş hücre seç (bulan +2 atışlık ulti kazanır)
  room.mines = new Set();
  const empties = [];
  for (let y = 0; y < room.h; y++) for (let x = 0; x < room.w; x++) { const kk = key(x, y); if (!room.occupancy.has(kk)) empties.push(kk); }
  for (let i = 0; i < 2 && empties.length; i++) { const idx = rnd(empties.length); room.mines.add(empties[idx]); empties.splice(idx, 1); }
  room.phase = "PLACING";
  for (const p of room.players.values()) {
    p.ready = !!p.bot;   // botlar otomatik hazır
    if (p.ws) send(p.ws, yourShipsMsg(room, p));
  }
  sendGmShips(room);
  room.log.push("Gemiler yerleştirildi, oyuncular onaylıyor…");
  broadcast(room);
}

function resetForRematch(room) {
  for (const p of room.players.values()) {
    p.alive = true; p.ready = false; p.ships = []; p.hits = 0; p.sunkCount = 0;
    p.score = 0; p.ultiScoreTier = 0;
  }
  room.mines = new Set();
  room.phase = "LOBBY";
  room.shots = new Map(); room.occupancy = new Map(); room.hitInfo = new Map();
  room.turnOrder = []; room.turnPos = 0; room.deadline = 0;
  room.sunkSeq = 0; room.sunkBy = ""; room.sunkOwner = ""; room.sunkCells = null; room.sunkByChar = 0;
  room.ultiPlayer = -1; room.ultiLeft = 0; room.ultiSeq = 0; room.ultiBy = "";
  room.epoch = 0;
  if (room.timer) { clearTimeout(room.timer); room.timer = null; }
  clearBot(room);
  room.log = ["Yeni oyun! Host başlatınca başlar"];
  broadcast(room);
}

function beginPlaying(room) {
  room.phase = "PLAYING";
  room.turnOrder = [...room.players.keys()].filter((id) => room.players.get(id).connected);
  room.turnPos = 0;
  room.epoch = 1; room.hitInfo = new Map();
  room.ultiPlayer = -1; room.ultiLeft = 0;
  room.log.push("Oyun başladı! Sıra: " + (room.players.get(currentTurnId(room))?.name || "-"));
  startTurnTimer(room);
  broadcast(room);
  scheduleBot(room);
}

// ---- Atış ----
function fire(room, shooter, x, y) {
  if (x < 0 || y < 0 || x >= room.w || y >= room.h) return;
  const k = key(x, y);
  if (room.shots.has(k)) return;
  const sp = room.players.get(shooter);
  if (!sp) return;

  const wasUlti = (room.ultiPlayer === shooter && room.ultiLeft > 0);
  let grantedUlti = false;
  const owner = room.occupancy.get(k);

  if (owner) {
    room.shots.set(k, { by: shooter, hit: true });
    room.hitInfo.set(k, { by: shooter, epoch: room.epoch });
    sp.hits++;
    const [ownerId, shipIdx] = owner;
    if (ownerId !== shooter) sp.score = (sp.score || 0) + 1;   // düşman isabeti +1 (kendi gemin sayılmaz)
    const ownerP = room.players.get(ownerId);
    const ship = ownerP && ownerP.ships ? ownerP.ships[shipIdx] : null;
    if (ship) {
      const ci = ship.cells.findIndex((c) => c[0] === x && c[1] === y);
      if (ci >= 0) ship.hit[ci] = true;
      if (shipSunk(ship)) {
        sp.sunkCount++;
        room.sunkSeq++; room.sunkBy = sp.name; room.sunkOwner = ownerP ? ownerP.name : "";
        room.sunkCells = ship.cells; room.sunkByChar = sp.char || 0;
        if (ownerId === shooter) room.log.push(`${sp.name} kendi gemisini batırdı!`);
        else room.log.push(`${sp.name}, ${ownerP ? ownerP.name : "?"} oyuncusunun gemisini batırdı`);
        // ULTI: düşman gemisinin TÜM parçaları bu oyuncu tarafından bu sırada vurulduysa
        if (ownerId !== shooter) {
          sp.score = (sp.score || 0) + 3;   // düşman gemisi batırma +3
          const solo = ship.cells.every((c) => {
            const hi = room.hitInfo.get(key(c[0], c[1]));
            return hi && hi.by === shooter && hi.epoch === room.epoch;
          });
          if (solo) {
            room.ultiPlayer = shooter;
            room.ultiLeft += 4;
            room.ultiSeq++;
            room.ultiBy = sp.name;
            room.log.push(`⚡ ${sp.name} ULTI kazandı!`);
            grantedUlti = true;
          }
        }
        if (ownerP && ownerP.alive && ownerP.ships.every((s) => shipSunk(s))) {
          ownerP.alive = false;
          room.turnOrder = room.turnOrder.filter((id) => id !== ownerId);
          room.log.push(`${ownerP.name} elendi!`);
        }
      }
    }
  } else {
    room.shots.set(k, { by: shooter, hit: false });
    // Gizli hazine bulundu → 2 atışlık ulti
    if (room.mines && room.mines.has(k)) {
      room.mines.delete(k);
      room.ultiPlayer = shooter; room.ultiLeft += 2; room.ultiSeq++; room.ultiBy = sp.name;
      room.log.push(`💣 ${sp.name} gizli hazineyi buldu! 2 atışlık ULTI!`);
      grantedUlti = true;
    }
  }

  // 7 puana ulaşınca ULTI (her 7 puanda bir, 4 atış). Bir atışta en fazla bir ulti verilir.
  {
    const tier = Math.floor((sp.score || 0) / 7);
    if (tier > (sp.ultiScoreTier || 0)) {
      sp.ultiScoreTier = tier;            // kilometre taşını tüket (bu atışta zaten ulti varsa bile)
      if (!grantedUlti) {
        room.ultiPlayer = shooter; room.ultiLeft += 4; room.ultiSeq++; room.ultiBy = sp.name;
        room.log.push(`⚡ ${sp.name} 7 puana ulaştı — ULTI!`);
        grantedUlti = true;
      }
    }
  }

  if (checkOver(room)) { room.ultiPlayer = -1; room.ultiLeft = 0; broadcast(room); return; }

  if (wasUlti) {
    // Bu atış bir ulti bombasıydı; bir hak tüket
    room.ultiLeft--;
    if (room.ultiLeft <= 0) { room.ultiPlayer = -1; advanceTurn(room); }
    else { startTurnTimer(room); }
  } else if (grantedUlti) {
    // Normal atışla ulti kazanıldı → ulti moduna gir, sıra aynı oyuncuda
    startTurnTimer(room);
  } else if (owner) {
    // Normal isabet → aynı oyuncu devam
    const sidx = room.turnOrder.indexOf(shooter);
    room.turnPos = sidx >= 0 ? sidx : room.turnPos % Math.max(1, room.turnOrder.length);
    startTurnTimer(room);
  } else {
    // Iska → sıra geçer
    advanceTurn(room);
  }
  broadcast(room);
  scheduleBot(room);
}

function advanceTurn(room) {
  if (room.turnOrder.length === 0) return;
  room.epoch++;
  const cur = currentTurnId(room);
  const idx = room.turnOrder.indexOf(cur);
  room.turnPos = idx === -1 ? 0 : (idx + 1) % room.turnOrder.length;
  startTurnTimer(room);
}

function checkOver(room) {
  const alive = [...room.players.values()].filter((p) => p.alive);
  if (room.phase === "PLAYING" && alive.length <= 1) {
    room.phase = "OVER";
    if (room.timer) { clearTimeout(room.timer); room.timer = null; }
    clearBot(room);
    room.log.push(alive.length ? `🏆 Kazanan: ${alive[0].name}` : "Oyun bitti");
    // Seri (art arda oyunlar) skorunu güncelle
    const winner = alive[0];
    if (winner) winner.wins = (winner.wins || 0) + 1;
    for (const p of room.players.values()) {
      p.tSunk = (p.tSunk || 0) + p.sunkCount;
      p.tHits = (p.tHits || 0) + p.hits;
      p.tScore = (p.tScore || 0) + (p.score || 0);
      p.games = (p.games || 0) + 1;
    }
    return true;
  }
  return false;
}

function startTurnTimer(room) {
  if (room.timer) clearTimeout(room.timer);
  room.deadline = Date.now() + room.turnSec * 1000;
  room.timer = setTimeout(() => onTurnTimeout(room), room.turnSec * 1000);
}

function onTurnTimeout(room) {
  if (room.phase !== "PLAYING") return;
  const cur = currentTurnId(room);
  if (cur === -1) return;
  const free = [];
  for (let yy = 0; yy < room.h; yy++) for (let xx = 0; xx < room.w; xx++) {
    if (!room.shots.has(key(xx, yy))) free.push([xx, yy]);
  }
  if (free.length === 0) { checkOver(room); broadcast(room); return; }
  const c = free[rnd(free.length)];
  room.log.push(`${room.players.get(cur)?.name}: süre doldu, otomatik atış`);
  fire(room, cur, c[0], c[1]);
}

// ---- Mesajlar ----
function yourShipsMsg(room, p) {
  return { t: "yourships", w: room.w, h: room.h, ships: (p.ships || []).map((s) => s.cells) };
}

function stateMsg(room) {
  const players = [...room.players.values()].map((p) => ({
    id: p.id, name: p.name, color: p.color, alive: p.alive, connected: p.connected,
    ready: p.ready, hits: p.hits, sunk: p.sunkCount, voice: !!p.voiceOn,
    wins: p.wins || 0, tSunk: p.tSunk || 0, tHits: p.tHits || 0, games: p.games || 0,
    score: p.score || 0, tScore: p.tScore || 0,
    char: p.char || 0, bot: !!p.bot,
    shipsLeft: (p.ships || []).filter((s) => !shipSunk(s)).length,
  }));
  const shots = [...room.shots.entries()].map(([k, s]) => {
    const [x, y] = k.split(",").map(Number);
    return { x, y, hit: s.hit, by: s.by };
  });
  const obj = {
    t: "state", phase: room.phase, host: room.hostId,
    ships: room.shipsPerPlayer, turnSec: room.turnSec,
    w: room.w, h: room.h,
    turn: room.phase === "PLAYING" ? currentTurnId(room) : -1,
    deadline: room.deadline,
    winner: room.phase === "OVER" ? ([...room.players.values()].find((p) => p.alive)?.id ?? -1) : -1,
    players, shots, log: room.log.slice(-8),
    sunkSeq: room.sunkSeq, sunkBy: room.sunkBy, sunkOwner: room.sunkOwner,
    sunkCells: room.sunkCells, sunkByChar: room.sunkByChar,
    ultiPlayer: room.ultiPlayer, ultiLeft: room.ultiLeft, ultiSeq: room.ultiSeq, ultiBy: room.ultiBy,
    code: room.code, gm: !!room.gm,
  };
  if (room.phase === "OVER") {
    const reveal = [];
    for (const p of room.players.values())
      for (const ship of (p.ships || []))
        reveal.push({ owner: p.id, color: p.color, cells: ship.cells });
    obj.reveal = reveal;
  }
  return obj;
}

// ---- Bağlantı ----
const server = http.createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
    res.end("ok rooms " + rooms.size);
    return;
  }
  // Çalışan sürümün commit'i (dağıtım doğrulaması için). /v/<herhangi> da aynı yanıtı verir.
  if (req.url === "/version" || req.url.startsWith("/v/")) {
    res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
    res.end(process.env.RENDER_GIT_COMMIT || "unknown");
    return;
  }
  // Web oyun istemcisini servis et
  res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
  res.end(PAGE || "Amiral Batti sunucusu calisiyor.");
});
const wss = new WebSocketServer({ server });

wss.on("connection", (ws) => {
  ws.roomCode = null;
  ws.playerId = -1;

  ws.on("message", (data) => {
    let m;
    try { m = JSON.parse(data.toString()); } catch (e) { return; }
    handle(ws, m);
  });
  ws.on("close", () => onDisconnect(ws));
  ws.on("error", () => {});
});

function handle(ws, m) {
  switch (m.t) {
    case "create": {
      const code = makeCode();
      const room = newRoom(code);
      rooms.set(code, room);
      const id = room.nextId++;
      const token = makeToken();
      ws.roomCode = code; ws.playerId = id;
      room.hostId = id;
      const p = { id, name: (m.name || "Oyuncu").slice(0, 14), color: 0, alive: true, ready: false, connected: true, hits: 0, sunkCount: 0, ships: [], voiceOn: false, wins: 0, tSunk: 0, tHits: 0, games: 0, score: 0, ultiScoreTier: 0, tScore: 0, char: 0, ws, token, graceTimer: null };
      room.players.set(id, p);
      send(ws, { t: "created", code, id, host: true, token });
      send(ws, { t: "welcome", id, host: true, token });
      broadcast(room);
      break;
    }
    case "createTest": {
      if (String(m.pass || "") !== "Amiral") { send(ws, { t: "error", msg: "Yanlış parola" }); return; }
      const code = makeCode();
      const room = newRoom(code);
      room.gm = true;
      rooms.set(code, room);
      const id = room.nextId++;
      const token = makeToken();
      ws.roomCode = code; ws.playerId = id;
      room.hostId = id;
      const p = { id, name: (m.name || "GM").slice(0, 14), color: 0, alive: true, ready: false, connected: true, hits: 0, sunkCount: 0, ships: [], voiceOn: false, wins: 0, tSunk: 0, tHits: 0, games: 0, score: 0, ultiScoreTier: 0, tScore: 0, char: (m.char | 0) || 0, ws, token, graceTimer: null };
      room.players.set(id, p);
      addBot(room);
      send(ws, { t: "created", code, id, host: true, token, gm: true });
      send(ws, { t: "welcome", id, host: true, token });
      broadcast(room);
      break;
    }
    case "join": {
      const code = (m.code || "").toUpperCase().trim();
      const room = rooms.get(code);
      if (!room) { send(ws, { t: "error", msg: "Oda bulunamadı: " + code }); return; }
      if (room.phase !== "LOBBY") { send(ws, { t: "error", msg: "Oyun başladı, katılım kapalı." }); return; }
      if (room.players.size >= 8) { send(ws, { t: "error", msg: "Oda dolu (en fazla 8)." }); return; }
      const id = room.nextId++;
      const token = makeToken();
      ws.roomCode = code; ws.playerId = id;
      const color = room.players.size % 8;
      const p = { id, name: (m.name || "Oyuncu").slice(0, 14), color, alive: true, ready: false, connected: true, hits: 0, sunkCount: 0, ships: [], voiceOn: false, wins: 0, tSunk: 0, tHits: 0, games: 0, score: 0, ultiScoreTier: 0, tScore: 0, char: 0, ws, token, graceTimer: null };
      room.players.set(id, p);
      send(ws, { t: "welcome", id, host: false, token });
      broadcast(room);
      break;
    }
    case "resume": {
      const code = (m.code || "").toUpperCase().trim();
      const room = rooms.get(code);
      if (!room) { send(ws, { t: "error", msg: "Oda artık yok" }); return; }
      let found = null;
      for (const p of room.players.values()) { if (p.token && p.token === m.token) { found = p; break; } }
      if (!found) { send(ws, { t: "error", msg: "Oturum bulunamadı" }); return; }
      if (found.graceTimer) { clearTimeout(found.graceTimer); found.graceTimer = null; }
      found.ws = ws; found.connected = true;
      ws.roomCode = code; ws.playerId = found.id;
      send(ws, { t: "welcome", id: found.id, host: found.id === room.hostId, token: found.token });
      if (room.phase !== "LOBBY") send(ws, yourShipsMsg(room, found));
      if (room.gm && found.id === room.hostId && room.phase !== "LOBBY") sendGmShips(room);
      room.log.push(`${found.name} yeniden bağlandı`);
      broadcast(room);
      break;
    }
    default: {
      const room = rooms.get(ws.roomCode);
      if (!room) return;
      const pid = ws.playerId;
      if (m.t === "config") {
        if (pid !== room.hostId || room.phase !== "LOBBY") return;
        room.shipsPerPlayer = Math.min(6, Math.max(2, m.ships | 0));
        room.turnSec = Math.min(120, Math.max(10, m.turnSec | 0));
        broadcast(room);
      } else if (m.t === "start") {
        if (pid !== room.hostId || room.phase !== "LOBBY") return;
        if (room.players.size < 2) { send(ws, { t: "error", msg: "En az 2 oyuncu gerekli." }); return; }
        startGame(room);
      } else if (m.t === "ready") {
        if (room.phase !== "PLACING") return;
        const p = room.players.get(pid); if (p) p.ready = true;
        if ([...room.players.values()].every((p) => !p.connected || p.ready)) beginPlaying(room);
        else broadcast(room);
      } else if (m.t === "fire") {
        if (room.phase !== "PLAYING") return;
        if (currentTurnId(room) !== pid) return;
        fire(room, pid, m.x | 0, m.y | 0);
      } else if (m.t === "rematch") {
        if (pid !== room.hostId || room.phase !== "OVER") return;
        resetForRematch(room);
      } else if (m.t === "char") {
        if (room.phase !== "LOBBY") return;
        const p = room.players.get(pid); if (p) p.char = Math.min(5, Math.max(0, m.c | 0));
        broadcast(room);
      } else if (m.t === "voice") {
        const p = room.players.get(pid); if (p) p.voiceOn = !!m.on;
        broadcast(room);
      } else if (m.t === "rtc") {
        // WebRTC sinyalini hedef oyuncuya ilet
        const target = room.players.get(m.to | 0);
        if (target && target.ws) send(target.ws, { t: "rtc", from: pid, data: m.data });
      } else if (m.t === "chat") {
        const p = room.players.get(pid); if (!p) return;
        const text = String(m.text || "").slice(0, 120).trim();
        if (!text) return;
        const msg = { t: "chat", from: pid, name: p.name, color: p.color, text };
        for (const pp of room.players.values()) if (pp.ws) send(pp.ws, msg);
      }
    }
  }
}

const GRACE_MS = 90000; // kısa kopukluklarda (uygulama değiştirme vb.) oyuncuyu hemen atma

function onDisconnect(ws) {
  const room = rooms.get(ws.roomCode);
  if (!room) return;
  const pid = ws.playerId;
  const p = room.players.get(pid);
  if (!p) return;
  if (p.ws !== ws) return; // eski/başka bağlantının kapanması (resume sonrası) — yok say
  p.connected = false;
  if (p.graceTimer) clearTimeout(p.graceTimer);
  p.graceTimer = setTimeout(() => finalizeLeave(room, pid), GRACE_MS);
  broadcast(room);
}

function finalizeLeave(room, pid) {
  const p = room.players.get(pid);
  if (!p || p.connected) return; // geri bağlandıysa iptal
  p.graceTimer = null;
  room.log.push(`${p.name} ayrıldı`);
  if (room.phase === "LOBBY") {
    room.players.delete(pid);
    if (pid === room.hostId) room.hostId = [...room.players.keys()][0] ?? -1;
  } else if (room.phase === "PLAYING" && p.alive) {
    p.alive = false;
    const wasTurn = currentTurnId(room) === pid;
    room.turnOrder = room.turnOrder.filter((id) => id !== pid);
    if (room.turnPos >= room.turnOrder.length) room.turnPos = 0;
    if (room.ultiPlayer === pid) { room.ultiPlayer = -1; room.ultiLeft = 0; }
    if (wasTurn) room.epoch++;
    if (!checkOver(room) && wasTurn) startTurnTimer(room);
  }
  const anyConn = [...room.players.values()].some((pp) => pp.connected && !pp.bot);
  const anyGrace = [...room.players.values()].some((pp) => pp.graceTimer);
  if (!anyConn && !anyGrace) {
    if (room.timer) clearTimeout(room.timer);
    clearBot(room);
    rooms.delete(room.code);
    return;
  }
  broadcast(room);
}

if (require.main === module) {
  server.listen(PORT, () => console.log("Amiral Batti sunucusu port " + PORT));
}

// Test için (yalnızca require edildiğinde kullanılır)
module.exports = { newRoom, fire, key, shipSunk, currentTurnId };
