<p align="center">
  <img src="assets/sigma_text.png" width="500">
</p>



# ΣTerm – Native Android Terminal

Gerçek bir Android uygulaması. Orijinal Termux script'inin neredeyse tüm komutları Kotlin ile yeniden yazıldı.

## Desteklenen Komutlar

| Komut | Açıklama |
|-------|----------|
| `ca <isim>` | Kullanıcı adı değiştir |
| `cd`, `ls` (-l/-a), `pwd` | Dizin işlemleri |
| `mkdir`, `touch`, `rm -r`, `cp`, `mv`, `cat` | Dosya işlemleri |
| `edit <dosya>` | Basit satır editörü (`:w` `:wq` `:q` `a` `d N`) |
| `get -<kaynak> <URL>` | Dosya indir (GitHub raw desteği) |
| `battery` | Pil durumu |
| `whoami`, `id` | Kullanıcı bilgisi |
| `history` / `!!` | Komut geçmişi |
| `alias isim=komut` | Alias |
| `neofetch` | Sistem bilgisi |
| `clear`, `echo`, `help`, `exit` | Diğer |

## Hızlı Başlangıç

1. Zip'i aç
2. Android Studio → **Open** → `ΣTerm-Android` klasörü
3. Run

veya:

```bash
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

## Notlar

- Tüm dosya işlemleri primary external storage altında sandbox'lanır.
- `arch` / `distro` / `git` gibi Termux'a özel komutlar native ortamda anlamsız olduğu için eklenmedi.
- `edit` komutu terminal içinde çalışır (orijinal script ile aynı mantık).

## Lisans

MIT

---

## Bu Sürümde Yapılan Değişiklikler

Bu proje, orijinal `sigma_term.sh` (Termux/bash) betiğinin GitHub Actions ile
kolayca `.apk`'ya derlenebilecek native bir Kotlin/Android sürümüdür.

**Kaldırılanlar** (talep üzerine, native ortamda anlamsız/gereksiz oldukları için):

- `arch` / `arch install` / `distro` — `proot-distro` tabanlı Arch Linux
  kurulum ve giriş komutları tamamen çıkarıldı.
- `git`, `github`, `gitea` — sistem komutlarına passthrough yapan bu üç komut
  kaldırıldı; native bir Android uygulamasında karşılığı yoktur.

**Native Android'e taşınırken uyarlananlar:**

- Dosya sistemi işlemleri artık Termux'un `/storage/emulated/0` yolunu
  gizleyen mantığı yerine, uygulamaya özel harici depolama alanını
  (`Context.getExternalFilesDir`) `~` olarak gösterip sandbox'lar; ekstra
  depolama izni istemez.
- `get` komutundaki indirme işlemi `HttpURLConnection` ile arka planda
  (coroutine/IO thread) çalışır; `-github` kaynağı için `blob` → `raw`
  URL dönüşümü korunmuştur. `--zip`/`--unzip` seçenekleri Kotlin'in
  `ZipInputStream`'i ile native olarak işlenir (harici `unzip` aracı
  gerekmez).
- `battery` komutu artık gerçek Android `BatteryManager` API'sinden veri
  okur (Termux:API gerekmez).
- `neofetch` komutu cihaz modeli, Android sürümü, RAM ve depolama
  kullanımını doğrudan Android sistem servislerinden (`ActivityManager`,
  `StatFs`) okuyarak gösterir.
- Hata mesajları `[X]` (kırmızı), bilgilendirme mesajları `[*]` (sarı)
  önekiyle gösterilmeye devam ediyor; bu davranış bash sürümüyle
  birebir aynı tutuldu.
- `alias` ve kullanıcı adı (`ca`) artık `SharedPreferences` ile kalıcı
  olarak saklanıyor (bash sürümündeki `.sigmatermrc` dosyasının karşılığı).
- Uygulama ikonu ve terminal içi logo, verilen ΣTerm görselleriyle
  güncellendi (`assets/sigma_text.png`, launcher ikonları).
- `.github/workflows/build-apk.yml` eklendi: her push'ta debug APK'yı
  otomatik derleyip artifact olarak yükler, wrapper dosyası gerektirmeden
  Gradle 8.7'yi doğrudan kurar.
