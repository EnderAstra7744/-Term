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

---

## v3 — Depolama İzni, Scripting Dili ve UI Güncellemeleri

Bu sürümde eklenenler çok geniş kapsamlı olduğu için ayrı bir bölümde
topladım. **Önemli:** Bazı komutların (`while`, `or`, `stgk`) orijinal
tarifin biraz belirsiz olduğu yerlerinde somut bir söz dizimi kararı
almam gerekti — aşağıda her biri açıkça yazılı. Test ederken bu tam
söz dizimlerini kullan.

### 1) Gerçek depolama erişimi

Artık uygulama varsayılan olarak kendi özel (sandbox) klasöründe
çalışıyor, ama `sigmaterm-storage-access` komutunu çalıştırınca:

- **Android 11+ (API 30+):** "Tüm dosyalara erişim" (All Files Access)
  ayar ekranını açar. İzin verirsen `~` artık gerçekten
  `/storage/emulated/0` olur.
- **Android 10 ve altı:** Klasik `WRITE_EXTERNAL_STORAGE` çalışma zamanı
  izni istenir.

İzin verildikten sonra (uygulamaya dönünce) otomatik olarak algılanır,
tekrar komut girmene gerek yok. `neofetch` çıktısında "Storage access:
full / sandboxed" satırından hangi modda olduğunu görebilirsin.

### 2) Scripting katmanı — değişkenler, fonksiyonlar, döngüler

Bu komutlar tek satırda çalışır ve `{ }` / `[ ]` içindeki değerler
**boşluksuz, virgülle ayrılmış** olmalı (basit boşluk-bazlı ayrıştırıcı
kullanıyoruz).

| Komut | Söz Dizimi | Açıklama |
|---|---|---|
| `variables` | `variables NAME =: VALUE [-y]` | Yeni değişken oluşturur |
| `set` | `set NAME =: VALUE` | Var olan değişkeni günceller (yoksa hata) |
| `setname` | `setname OLD =: NEW` | Değişkeni yeniden adlandırır |
| `unset` | `unset [-fv] NAME... [-y]` | Değişken(ler)i siler; `-fv` yoksa da hata vermez |
| `env` | `env` | Tüm değişkenleri `[1] name -> value` formatında listeler |
| `echo` | `echo bir $\`NAME iki` | `` $`NAME `` kalıbı değişkenin değeriyle değiştirilir |
| `function` | `function NAME [{p1,p2}] do:` ... `end` | Çok satırlı fonksiyon tanımı; `do:` sonrası her satır fonksiyon gövdesine eklenir, `end` bitirir |
| `return` | `return VALUE` | Sadece fonksiyon gövdesi içinde geçerli; değeri döndürüp fonksiyonu sonlandırır, `RETURN` değişkenine de yazar |
| `if` | `if {exp1} {OP} {exp2} do: {func}` | OP: `==` `!=` `>` `<` `>=` `<=` (sayısalsa sayı, değilse metin karşılaştırması) |
| `else` | `else do: {func}` | Bir önceki `if` false ise çalışır; `if` olmadan kullanılırsa hata verir |
| `for` | `for {a,b,c} do: {func}` veya `for {1-5} do: {func}` | Liste ya da sayısal aralık üzerinde döner, her öğeyi fonksiyona ilk parametre olarak (veya parametre tanımlı değilse `IT` değişkenine) geçirir |
| `while` | `while {cond} do: {func}` | `cond` bir değişken adı (truthy kontrolü) ya da `count<10` gibi boşluksuz bir karşılaştırma olabilir; güvenlik amaçlı 100.000 iterasyon sınırı var |
| `break` | `break` | En yakın çalışan `for`/`while` döngüsünü durdurur (ekstra bayraklar/hedef argümanları şimdilik yalnızca kabul edilir, ayrım yapılmaz) |
| `or` | `or %70 {a,b} do: {funcA,funcB}` | %70 ihtimalle `funcA(a)`, %30 ihtimalle `funcB(b)` çalışır |

**Örnek:**
```
function greet {name} do:
echo Hello $`name
end

for {Ender,Alice,Bob} do: greet
```

**Fonksiyonlar hakkında önemli not:** Parametreler global değişken
olarak atanır (gerçek bir lexical scope yok) — yani bir fonksiyonu
çağırmak, aynı isimde global bir değişken varsa onun üzerine yazar.
Basit scriptler için yeterli ama iç içe/özyinelemeli fonksiyonlarda
dikkatli olmak gerekir.

### 3) Diğer yeni komutlar

| Komut | Söz Dizimi | Açıklama |
|---|---|---|
| `hash` | `hash <md5\|sha1\|sha256\|sha512> <metin>` | Not: "sha264" diye bir algoritma yok, `sha256` kastedildiğini varsaydım |
| `chmod` | `chmod <+x\|-x\|+w\|-w\|+r\|-r\|OKTAL_RAKAM> <dosya>` | Android `File` API'si sadece owner rwx bitlerini destekliyor; tam Unix chmod semantiği yok |
| `siterm` | `siterm <script.sh> [arg1] [arg2] ...` | Termux'taki `bash script.sh` karşılığı: dosyadaki her satırı sırayla ΣTerm komutu olarak çalıştırır, `$1`/`$2`/... argümanlarla değiştirilir, `#` ile başlayan satırlar yorum sayılır |
| `requ` | `requ <GET\|POST\|PUT\|DELETE\|PATCH> <URL> [gövde]` | HTTP isteği atar, durum kodu + yanıt gövdesini (ilk 500 karakter) yazdırır |
| `stgk` | `stgk install\|uninstall\|remove\|show\|list\|list-installed\|list-upgradeable\|update\|upgrade [isim] [-y]` | Aşağıya bak |

### 4) `stgk` paket yöneticisi — ⚠️ önemli sınırlama

`stgk`, `https://raw.githubusercontent.com/EnderAstra7744/sigma-stgk-sage/main/packages.json`
adresinden bir paket indeksi (JSON dizisi, her öğe
`{"name":..., "version":..., "url":..., "description":...}` formatında)
çekmeyi varsayıyor. Bu format tamamen benim varsaydığım bir kural —
gerçek reponun yapısı buysa harika, değilse `doStgk` fonksiyonunu
(`TerminalEngine.kt`) reponun gerçek yapısına göre güncellemem gerekir.

**Repo private olduğu için** `raw.githubusercontent.com` isteği
muhtemelen 404 dönecektir — private repo'lara anonim erişim yoktur.
Bunun için iki seçenek var:
1. Reponun `packages.json` kısmını public yapmak (repo private kalsa
   bile GitHub Pages veya ayrı public bir repo ile paylaşılabilir), ya da
2. Uygulamaya bir GitHub Personal Access Token ekleme akışı eklemem
   (örn. `stgk auth <token>` komutu, `Authorization: token ...` header'ı
   ile). Bunu istersen ayrıca ekleyebilirim — şu an güvenlik nedeniyle
   koda gömülü bir token yok.

`install`, `upgrade`, indirilen dosyayı `~/.sigmaterm/stgk/packages/`
altına koyar; kurulum kaydı `~/.sigmaterm/stgk/installed.json`'da
tutulur.

### 5) UI güncellemeleri

- **Blok imleç:** `commandInput`'un imleci artık Termux'a benzer şekilde
  daha kalın/beyaz bir dikdörtgen (`terminal_cursor.xml`), varsayılan
  ince çizgi yerine. (Not: Android'in karakter hücresini tam kaplayan
  gerçek bir blok imleç için özel bir metin render motoru gerekir; bu
  bir yaklaşık/iyileştirilmiş versiyondur.)
- **Klavye tık sesi:** Her karakter eklendiğinde (silme hariç)
  `AudioManager.FX_KEYPRESS_STANDARD` sistem sesi çalınır.
- **`neofetch` düzeltmesi:** ASCII logo ve 2 satırlık renk paleti
  (8 normal + 8 parlak renk) artık gerçekten gösteriliyor; önceki
  sürümde bunlar unutulmuştu.
- **İngilizce çıktılar:** Uygulamanın tüm komut çıktıları, hata/bilgi
  mesajları ve `help` açıklamaları artık İngilizce (global kullanıcı
  kitlesi için). Bu README ve kod yorumları Türkçe kalmaya devam
  ediyor.

### Bilinen sınırlamalar / test etmen gerekenler

- `while`/`for`/`or`/`if`/`else`/`function` söz dizimleri bu README'de
  yazıldığı gibi **kesin** olmalı (boşluk toleransı düşük); gerçek
  cihazda dener misin, ihtiyaç olursa ayrıştırmayı gevşetirim.
- `break`'in `-y`/`/s`/`a`/`@` bayrakları ve `PROCESS`/`OR`/`FOR` hedef
  argümanı şu an sadece kabul ediliyor, davranışı değiştirmiyor —
  spesifik bir davranış istiyorsan (örn. sadece `or`'u durdur, `for`'u
  durdurma) söyle, ayrı ayrı takip eden bir sisteme genişletirim.
- `stgk` gerçek reponla test edilmedi (repo private + format varsayım).

---

## v4 — Token Yönetimi, Pinch-to-Zoom, neofetch Yan Yana Düzen

### 1) `sigmaterm-change-token`

`stgk`'nin private repoya erişebilmesi için bir GitHub token seçme
menüsü ekler. Komutu çalıştırınca iki seçenekli bir dialog açılır:

- **Manual Token** — Alttan bir metin alanı çıkar, token'ı buraya
  yapıştırırsın. `SharedPreferences`'da saklanır (düz metin — cihaz
  root'lanmışsa veya adb backup alınırsa okunabilir, unutma).
- **Automatic Token** — `TerminalEngine.kt` içindeki
  `BUILTIN_GITHUB_TOKEN` sabitini kullanır.

⚠️ **Güvenlik notu:** `BUILTIN_GITHUB_TOKEN` şu an **boş** bırakıldı.
Ben sana gerçek bir token veremem/gömemem — APK'ya gömülen her şey
decompile edilince görülebilir. "Automatic Token"ı gerçekten
kullanmak istiyorsan:

1. GitHub'da sadece `sigma-stgk-sage` reposuna salt-okunur erişimi
   olan **fine-grained bir Personal Access Token** oluştur (repo'yu
   yönetebilecek geniş yetkili bir token asla kullanma).
2. `TerminalEngine.kt`'de `BUILTIN_GITHUB_TOKEN = ""` satırını
   `BUILTIN_GITHUB_TOKEN = "github_pat_..."` yap.
3. **Bu repoyu asla public yapma / bu haliyle GitHub'a push etme** —
   token'ı APK içinde taşımak, o token'ı isteyen herkese açık hale
   getirmek demektir. Bu satırı sadece kendi yerel/private build
   makinende değiştirip derle.

Alternatif (daha güvenli) yol: Automatic Token'ı hiç kullanma, herkes
kendi Manual Token'ını girsin.

### 2) İki parmakla yakınlaştırma (pinch-to-zoom)

Ekranın herhangi bir yerinde iki parmakla açıp-kapatma hareketi yazı
boyutunu (`9sp`–`26sp` arası) değiştirir; çıktı ekranı, komut satırı ve
prompt aynı anda ölçeklenir. Seçilen boyut cihazda kalıcı olarak
saklanır (`SharedPreferences`), bir sonraki açılışta hatırlanır.

### 3) `neofetch` — real Android ascii art from upstream neofetch (v5 update)

`neofetch` now shows the **logo on the left, info on the right**
(you flagged that the first version had these reversed — fixed).

**v5 change:** you pasted the actual `dylanaraps/neofetch` source, so
I replaced my earlier hand-made "brand badge" placeholder with the
**real, verbatim Android ascii art** from that script (the `"Android"*`
entry in `get_distro_ascii()`), ported into `androidAsciiLogo` in
`TerminalEngine.kt`. Two honest caveats about the port:

- neofetch colors that logo two-tone (`set_colors 2 7`: green body,
  a couple of white/gray accent dots on the "eyes" row). Our renderer
  colors a whole logo line with one color at a time, so the port is
  single-tone green — the only visual detail lost.
- The info fields (OS/Host/Kernel/Uptime/Shell/Memory/Disk) now follow
  neofetch's default `print_info()` labels and title/underline
  layout, adapted to what's actually meaningful on Android (no
  DE/WM/GPU driver/package manager fields, since those don't apply
  here).

The `android_small` variant and the ~150 other distro logos in that
script were **not** ported — this app only ever runs on Android, so
they'd be dead code.

### License compliance (MIT)

Because this app now reproduces an actual code excerpt from
`dylanaraps/neofetch` (the Android ascii art data), the MIT license
requires the copyright notice and permission text to be included.
Full text:

```
The MIT License (MIT)

Copyright (c) 2015-2021 Dylan Araps

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Source: <https://github.com/dylanaraps/neofetch> — only the Android
ascii art block was reused; the rest of ΣTerm is original code under
this project's own license.

