<p align="center">
  <img src="app/src/main/assets/sigma_text.png" width="500">
</p>



# ΣTerm – Native Android Terminal

Gerçek bir Android uygulaması. Orijinal Termux script’inin neredeyse tüm komutları Kotlin ile yeniden yazıldı.

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
| `theme <isim>` | Renk teması (gray, red, green, blue, purple, cyan, yellow, matrix) |
| `fortune` / `quote` | Rastgele söz |
| `ascii <metin>` | Büyük ASCII harfler |
| `portal set/del/<isim>` | Dizin kısayolu (bookmark) |
| `snapshot` / `restore` | Dizin dosya listesi anlık görüntüsü ve karşılaştırma |
| `diskmap` | Alt klasör boyut bar-chart |
| `lock` / `unlock` | Parola ile kilit |
| `stats` | Oturum istatistikleri |
| `neofetch` | Sistem bilgisi |
| `clear`, `echo`, `help`, `exit` | Diğer |

## Hızlı Başlangıç

1. Zip’i aç
2. Android Studio → **Open** → `SigmaTerm-Android` klasörü
3. Run

veya:

```bash
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions ile APK

Projeyi GitHub’a push’ladıktan sonra **Actions** sekmesinden “Build APK” workflow’u otomatik çalışır.

Manuel tetiklemek için:

```
Actions → Build APK → Run workflow
```

Artifact olarak `SigmaTerm-debug` APK’sı indirilebilir.

## Notlar

- Tüm dosya işlemleri primary external storage (`/storage/emulated/0`) altında sandbox’lanır.
- `arch` / `distro` / `proot-distro` ve `git` / `github` / `gitea` komutları **kasıtlı olarak kaldırıldı** (native Android ortamında anlamsız / gereksiz bağımlılık).
- `edit` komutu terminal içinde çalışır mantığı korunmuştur; UI entegrasyonu basit tutulmuştur.
- Depolama erişimi için Android 11+ cihazlarda “Tüm dosyalara erişim” izni istenir.
- Orijinal bash script’in (σTerm) config, alias, portal, snapshot, theme, lock, fortune, ascii, diskmap gibi yaratıcı özellikleri Kotlin’e taşındı.

## Yapılan Değişiklikler (orijinal script → native)

| Özellik | Durum |
|---------|-------|
| proot-distro / arch / distro | **Kaldırıldı** |
| git / github / gitea passthrough | **Kaldırıldı** |
| Termux:API (note, vibrate, toast) | Basitleştirildi / native API’ye geçirildi (battery tam, diğerleri opsiyonel) |
| Dosya sistemi sandbox | Korundu (`resolveInBase`) |
| Config (`.sigmatermrc`) | Shared filesDir’e taşındı |
| Prompt, tema, history, alias | Tamamen yeniden yazıldı |
| get (download) | HttpURLConnection ile native |
| neofetch | Android Build bilgileri ile güncellendi |

## Lisans

MIT
