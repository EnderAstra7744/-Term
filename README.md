# DTFA — Debian Terminal for Android

Verdiğin `debian-rootfs-arm64.tar.xz` dosyasını içine gömen, **root gerektirmeyen**
(proot tabanlı), Android için native bir Debian terminal uygulaması. GitHub
Actions ile senin hiçbir yerel Android Studio kurulumuna gerek kalmadan APK
olarak derlenir.

Logo: gönderdiğin Debian swirl PNG'sinden otomatik üretildi (`ic_launcher`,
tüm yoğunluklar için). Terminal fontu: gönderdiğin **Terminus.ttf**.

## Nasıl çalışıyor?

1. Uygulama ilk açılışta `assets/rootfs/debian-rootfs-arm64.tar.xz` dosyasını
   uygulamanın özel depolama alanına (`/data/data/com.dtfa.terminal/files/debian`)
   çıkarır (`RootfsInstaller.kt`). Bu işlem sadece bir kere yapılır.
2. Ardından **proot** çalıştırılır: `proot -r <rootfs> -0 -b /dev -b /proc -b /sys -w /root /bin/bash --login`
   proot; root yetkisi olmadan "sahte chroot" yapar — dosya yolu çağrılarını
   ptrace ile yakalayıp gerçek rootfs klasörüne yönlendirir. Bu yüzden **root
   gerekmez**.
3. Bu komut gerçek bir Linux **pty** (pseudo-terminal) içinde çalışır
   (`app/src/main/cpp/pty.c` — `forkpty()` kullanan küçük bir JNI köprüsü).
   Bu sayede iş kontrolü, Ctrl+C, doğru ortam değişkenleri gibi şeyler normal
   bir terminal gibi çalışır.
4. `TerminalActivity` çıktı byte'larını okuyup basit bir ANSI-temizleyiciden
   (`AnsiLiteTerminal.kt`) geçirip ekrana yazıyor, senin yazdığın komutları da
   pty'nin giriş ucuna gönderiyor.

### Terminal emülasyonu hakkında önemli not

`AnsiLiteTerminal`, **tam bir VT100/xterm emülatörü değil.** Renk kodlarını ve
imleç konumlandırma dizilerini (CSI/OSC escape kodları) temizler, `\r`, `\n`,
backspace'i doğru yorumlar. Bu, günlük kullanımın (bash, `ls`, `cd`, `apt`,
`pip`, `git`, `gcc`, `python`, ...) büyük kısmı için yeterlidir.

**Ama** `vim`, `nano`, `htop`, `less` gibi tam ekran / imleç-hareketli
programlar düzgün görünmeyecektir (çünkü onlar ekranın belirli noktalarına
"atlayarak" çizim yapar, biz bunu yorumlamıyoruz).

Eğer ileride tam bir terminal (renkler, imleç hareketi, vim/htop desteği)
istersen, en pratik yol Termux'un açık kaynak `terminal-view` +
`terminal-emulator` kütüphanelerini (JitPack üzerinden) `TerminalActivity`
içine entegre etmek olur; şu anki `PtyNative`/`TerminalSession` katmanı zaten
ham pty akışını sağladığı için o kütüphanelerin beklediği veriye yakın bir
noktadasın.

## Proje yapısı

```
DTFA/
├── .github/workflows/build.yml     ← GitHub Actions: APK'yı otomatik derler
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/                    ← pty.c + CMakeLists.txt (native köprü)
│       ├── java/com/dtfa/terminal/
│       │   ├── MainActivity.kt         (ilk açılış / rootfs çıkarma ekranı)
│       │   ├── TerminalActivity.kt     (terminal ekranı)
│       │   ├── TerminalSession.kt      (proot komutunu kurar, pty'yi yönetir)
│       │   ├── PtyNative.kt            (JNI köprüsü)
│       │   ├── AnsiLiteTerminal.kt     (basit ANSI temizleyici / satır tamponu)
│       │   └── RootfsInstaller.kt      (tar.xz çıkarma)
│       ├── assets/rootfs/debian-rootfs-arm64.tar.xz   ← senin dosyan
│       └── res/  (ikonlar, terminus.ttf, layout'lar, string'ler)
├── build.gradle, settings.gradle, gradle.properties
├── .gitattributes                  ← rootfs dosyasını Git LFS'e yönlendirir
└── .gitignore
```

## GitHub'a nasıl yükleyip APK alırsın?

1. Bu klasörün tamamını yeni bir GitHub reposuna it (push).
   Rootfs dosyası ~78 MB olduğu için **Git LFS kullanmanı öneririm**:
   ```bash
   git lfs install
   git add .gitattributes
   git add -A
   git commit -m "DTFA ilk sürüm"
   git push
   ```
   (Git LFS kurulu değilse `.gitattributes` dosyasını silip normal `git add/commit/push`
   de yapabilirsin — 78 MB, GitHub'ın 100 MB sert sınırının altında, sadece LFS
   olmadan repo biraz şişer.)

2. Push ettiğinde `.github/workflows/build.yml` otomatik çalışır. GitHub
   reposunda **Actions** sekmesine gir, "Build DTFA APK" işini aç, bitince
   **Artifacts** kısmından `DTFA-debug-apk` dosyasını indir — içinde kurulmaya
   hazır `.apk` var.

3. APK'yı telefona kopyala, "bilinmeyen kaynaklardan yükleme"yi aç, kur.
   İlk açılışta rootfs çıkarma işlemi biraz sürecek (dosya sayısına göre
   30 saniye - birkaç dakika arası), sonra doğrudan Debian kabuğuna düşersin.

### `proot` binary kaynağı (önemli, dikkat et)

Workflow, `proot`'un statik derlenmiş arm64 sürümünü şu adresten indirip
`libproot.so` adıyla `jniLibs/arm64-v8a/` içine koyuyor (bu isim hilesi,
Android'in bu dosyayı kurulumda gerçek/çalıştırılabilir bir dosya olarak
diske açmasını sağlıyor — yeni Android sürümlerinde çalışma zamanında
kendi kendine açılan dosyaları çalıştırma kısıtlamasını böyle aşıyoruz):

```
https://raw.githubusercontent.com/foxytouxxx/freeroot/main/proot
```

Bu, "root'suz proot ile rootfs çalıştır" işini yapan bilinen açık kaynak
projelerin kullandığı statik bir binary. Eğer bu URL ileride değişir/kaybolursa
workflow'daki "Fetch static proot" adımı kırılır — o zaman iki alternatif:

- **Termux paket deposu:** `packages.termux.dev` üzerinden `proot` .deb
  paketini çekmek — ama bu binary Termux'un kendi `$PREFIX/lib` altındaki
  paylaşımlı kütüphanelerine bağımlı olduğundan, o kütüphaneleri de (libtalloc
  vb.) birlikte taşımadan çalışmaz. Daha zahmetli.
- **Kaynak koddan derlemek:** workflow'a proot'u NDK ile kaynağından derleyen
  bir adım eklemek (termux-packages reposundaki `proot` paketi ve Android/bionic
  yamalarını referans alarak). En sağlam ama en uzun yol.

### İmzalama

Workflow şu an sadece **debug APK** üretiyor (debug keystore ile otomatik
imzalanır, doğrudan kurulabilir ama Play Store'a yüklenemez). Play Store'a
yüklemek istersen `app/build.gradle` içindeki `release` bloğuna kendi
imzalama anahtarını (keystore) GitHub Secrets üzerinden eklemen gerekir —
istersen bunu da ekleyebilirim.

## Sınırlamalar / bilinçli basitleştirmeler

- Sadece **arm64-v8a** destekleniyor (rootfs bu mimari için, uygulama da
  buna göre kısıtlı — `abiFilters`).
- Terminal tam VT100 değil (yukarıda anlatıldı).
- Ağ: proot ayrı bir network namespace açmıyor, uygulamanın kendi ağ
  bağlantısını kullanıyor — yani `apt update`/`pip install` içeride de
  çalışır, telefonun internetine ihtiyaç var.
- Depolama izni istemiyoruz çünkü her şey uygulamanın kendi private
  klasöründe (`/data/data/com.dtfa.terminal/files/debian`) tutuluyor —
  bu da uygulama silinince rootfs'un da otomatik silinmesi anlamına gelir.
