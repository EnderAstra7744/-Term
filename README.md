<p align="center">
  <img src="assets/sigma_text.png" width="500">
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
| `neofetch` | Sistem bilgisi |
| `clear`, `echo`, `help`, `exit` | Diğer |

## Hızlı Başlangıç

1. Zip’i aç
2. Android Studio → **Open** → `ΣTerm-Android` klasörü
3. Run

veya:

```bash
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

## Notlar

- Tüm dosya işlemleri primary external storage altında sandbox’lanır.
- `arch` / `distro` / `git` gibi Termux’a özel komutlar native ortamda anlamsız olduğu için eklenmedi.
- `edit` komutu terminal içinde çalışır (orijinal script ile aynı mantık).

## Lisans

MIT
