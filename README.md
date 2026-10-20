# Amiral Battı (Android, yerel ağ çok oyunculu)

Aynı WiFi/hotspot üzerindeki kişilerin birlikte oynadığı Amiral Battı.

## Nasıl oynanır
1. Biri **Oda Kur**'a basar (host). Ekranda bir **QR kod** ve **IP:port** çıkar.
2. Diğerleri **Odaya Katıl → QR okut** (ya da kodu elle girer).
3. Host **gemi sayısı** ve **el süresini** ayarlar, **Başlat**'a basar.
4. Sistem herkese tek ortak haritada gemilerini yerleştirir; herkes **Onayla**'ya basar.
5. Sırayla ateş edilir. **İsabette tekrar atarsın**, ıskada sıra geçer.
6. Gemileri biten **elenir**, **son kalan kazanır**.

- Tek ortak harita: kendi gemilerini görürsün, rakip gemileri gizlidir; isabet/ıska işaretleri herkese görünür.
- El süresi dolarsa sistem senin yerine rastgele atar.
- İnternet gerekmez; host sunucu, diğerleri soketle bağlanır.

## Teknoloji
- Kotlin + Jetpack Compose
- TCP soket (host = sunucu), JSON protokol
- ZXing (QR üretimi + tarama)
- Android 8.0+ (minSdk 26)

## APK
Her `push` sonrası GitHub Actions APK'yı derler ve **Releases › son-surum** altında
`AmiralBatti.apk` olarak yayınlar.

## Not
Bazı hotspot'lar cihazları birbirinden yalıtır (AP isolation); öyleyse bağlantı kurulmaz.
Test için en az 2 cihaz gerekir.
