# ⚓ Amiral Battı — Online Çok Oyunculu

Tarayıcıdan oynanan, internet üzerinden çok oyunculu Amiral Battı.
**iOS, Android ve PC** aynı anda, tek bir bağlantıyla birlikte oynar — kurulum yok.

## 🎮 Oyna
Oda kurup arkadaşlarına **4 harfli kodu** gönder, onlar da koda katılsın.

> Oyun tek bir ortak harita üzerinde oynanır: kendi gemilerini görürsün, rakip gemileri gizlidir;
> herkesin attığı isabet/ıska işaretleri paylaşılır.

### Nasıl oynanır
1. İsmini yaz → **Oda Kur** (bir kod üretir) ya da **Koda Katıl**.
2. Host gemi sayısını ve el süresini ayarlar, **Başlat**'a basar.
3. Sistem herkesin gemilerini ortak haritaya yerleştirir; herkes **Onayla**'ya basar.
4. Sırayla ateş: **isabette tekrar atarsın**, ıskada sıra geçer.
5. Gemileri biten **elenir**, **son kalan kazanır**. Oyun bitince **Tekrar Oyna** ile aynı odada yeni tur.

## ✨ Özellikler
- **Oda kodu** ile buluşma (aynı ağ gerekmez), 2–8 oyuncu.
- **Tetris benzeri gemi şekilleri**: düz, L, T, S/Z zikzak, 2×2 kare — rastgele döndürülüp yerleştirilir.
- **ULTI**: Bir gemiyi tek sıranda tek başına batırırsan 4 bombalık ulti kazanırsın (zincirlenebilir).
- **Efektler**: vuruş patlaması, ekran sarsıntısı, titreşim, batırma uyarısı, konfeti, sıra parlaması.
- **Radyo**: oyun oynarken dinlenebilen Türkçe istasyonlar.
- **Otomatik yeniden bağlanma**: uygulama değiştirip geri dönünce aynı odaya bağlanırsın.
- **Tekrar oyna**: oyun bitince aynı oda/kodla yeni oyun.

## 🖥️ Sunucu (kendin çalıştırmak istersen)
Oyun sunucusu `server/` klasöründe (Node.js + WebSocket). Statik web istemcisi de aynı sunucudan servis edilir.

### Ücretsiz dağıtım (Render)
`render.yaml` hazır. Render'da **New → Blueprint** ya da **Web Service** ile depoyu bağla:
- Root Directory: `server`
- Build: `npm install`
- Start: `node server.js`

### Yerelde
```bash
cd server
npm install
node server.js      # http://localhost:8080
```

## 🛠️ Teknoloji
- Sunucu: Node.js + `ws` (WebSocket). Oyun kuralları sunucuda (authoritative), birden çok oda.
- İstemci: tek dosyalık HTML/CSS/JS (çerçeve yok). HLS radyo için hls.js.

---
designed by Buğra
