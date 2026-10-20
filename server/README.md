# Amiral Battı — İnternet Sunucusu (ücretsiz kurulum)

Oyunun internet (WebSocket) sunucusu. Oda kodu ile buluşma + tüm oyun kuralları.
Aynı anda birden çok oda çalışır. **Ücretsiz** host edilebilir.

## Render ile ücretsiz kurulum (önerilen)
1. https://render.com → ücretsiz hesap aç (GitHub ile giriş yapabilirsin).
2. **New +** → **Web Service**.
3. GitHub deposu olarak **LyraN66/BattleShip**'i seç (Render'a erişim izni ver).
4. Ayarlar:
   - **Root Directory:** `server`
   - **Runtime:** Node
   - **Build Command:** `npm install`
   - **Start Command:** `node server.js`
   - **Instance Type:** Free
5. **Create Web Service** → birkaç dakikada yayınlanır.
6. Sana `https://amiral-batti-xxxx.onrender.com` gibi bir adres verir.
   Uygulamada **🌐 İnternetten Oyna → Sunucu adresi** kutusuna bu adresi yapıştır
   (baştaki `https://` olsa da olmasa da olur).

> Not: Ücretsiz katman 15 dk işlem olmazsa uyur; ilk bağlantı ~30-50 sn gecikebilir.
> Oyun başlayınca sorun olmaz.

## Alternatif: kendi sunucunda (VPS)
```
cd server
npm install
PORT=8080 node server.js
```
Uygulamada adres olarak `ws://SUNUCU_IP:8080` yaz (TLS varsa `wss://alanadi`).

## Kendi alan adın (yerlipos.com) altında göstermek (opsiyonel)
Natro DNS panelinde bir **CNAME** kaydı ekle:
`oyun.yerlipos.com → amiral-batti-xxxx.onrender.com`
Sonra Render'da **Custom Domain** olarak `oyun.yerlipos.com` ekle.
Uygulamada adres olarak `oyun.yerlipos.com` yazman yeterli.
