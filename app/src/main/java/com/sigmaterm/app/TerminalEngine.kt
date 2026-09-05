package com.sigmaterm.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

enum class LineType { NORMAL, ERROR, INFO, ACCENT, DIM, ECHO }

data class Line(val text: String, val type: LineType = LineType.NORMAL)

/**
 * ΣTerm'in komut motoru. Orijinal Termux/bash betiğindeki mantığın
 * Kotlin/native Android karşılığıdır.
 *
 * Kaldırılan özellikler (APK derlemesi için gerekli değil / anlamsız):
 *  - arch / distro (proot-distro tabanlı Arch Linux kurulumu)
 *  - git / github / gitea (gerçek sistem komutlarına passthrough)
 *
 * Tüm dosya işlemleri uygulamaya özel harici depolama alanı
 * (getExternalFilesDir) altında sandbox'lanır; ekstra depolama izni
 * gerekmez.
 */
class TerminalEngine(private val context: Context) {

    val baseDir: File = context.getExternalFilesDir(null) ?: context.filesDir
    var curDir: File = baseDir
        private set

    var userName: String = "unknown"
        private set

    val history = mutableListOf<String>()
    val aliases = linkedMapOf<String, String>()

    // Basit editör durumu
    private var editMode = false
    private var editTarget: File? = null
    private val editLines = mutableListOf<String>()

    private val prefs = context.getSharedPreferences("sigmaterm_prefs", Context.MODE_PRIVATE)

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
        loadConfig()
    }

    // -----------------------------------------------------
    // Config
    // -----------------------------------------------------

    private fun loadConfig() {
        userName = prefs.getString("user_name", "unknown") ?: "unknown"
        val aliasRaw = prefs.getString("aliases", "") ?: ""
        aliasRaw.split("\n").filter { it.contains("=") }.forEach { line ->
            val idx = line.indexOf('=')
            val k = line.substring(0, idx)
            val v = line.substring(idx + 1)
            if (k.isNotBlank()) aliases[k] = v
        }
    }

    private fun saveConfig() {
        prefs.edit()
            .putString("user_name", userName)
            .putString("aliases", aliases.entries.joinToString("\n") { "${it.key}=${it.value}" })
            .apply()
    }

    // -----------------------------------------------------
    // Yardımcılar
    // -----------------------------------------------------

    fun promptLabel(): String = "$userName@\u03A3Term:${hidePath(curDir)}\u03A3 \$ "

    fun hidePath(f: File): String {
        val base = baseDir.absolutePath
        val p = f.absolutePath
        return when {
            p == base -> "~"
            p.startsWith("$base/") -> "~" + p.substring(base.length)
            else -> "~"
        }
    }

    /** arg'ı curDir'e göre çözer; BASE_DIR dışına çıkarsa null döner. */
    private fun resolveInBase(arg: String): File? {
        val target = if (arg.startsWith("/")) File(baseDir, arg.removePrefix("/"))
        else File(curDir, arg)
        val normalized = try { target.canonicalFile } catch (e: Exception) { target.absoluteFile }
        val baseCanon = try { baseDir.canonicalFile } catch (e: Exception) { baseDir.absoluteFile }
        return if (normalized == baseCanon || normalized.absolutePath.startsWith(baseCanon.absolutePath + "/")) {
            normalized
        } else null
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = arrayOf("K", "M", "G", "T")
        var value = bytes.toDouble()
        var idx = -1
        while (value >= 1024 && idx < units.size - 1) {
            value /= 1024
            idx++
        }
        return if (idx < 0) "${bytes}B" else String.format("%.1f%s", value, units[idx])
    }

    // -----------------------------------------------------
    // Ana giriş noktası
    // -----------------------------------------------------

    suspend fun process(rawInput: String): List<Line> {
        val out = mutableListOf<Line>()
        var input = rawInput

        if (editMode) {
            return handleEditInput(input)
        }

        if (input == "!!") {
            if (history.isEmpty()) {
                out.add(Line("history: çalıştırılacak önceki komut yok", LineType.ERROR))
                return out
            }
            input = history.last()
            out.add(Line(input, LineType.ECHO))
        }

        if (input.isBlank()) return out

        var parts = input.trim().split(Regex("\\s+"))
        var cmd = parts.getOrElse(0) { "" }

        // Alias çözümlemesi
        aliases[cmd]?.let { aliasValue ->
            val aliasParts = aliasValue.trim().split(Regex("\\s+"))
            parts = aliasParts + parts.drop(1)
            cmd = parts.getOrElse(0) { "" }
        }

        history.add(input)
        val args = parts.drop(1)

        when (cmd) {
            "ca" -> {
                val name = args.getOrNull(0)
                if (name.isNullOrBlank()) {
                    out.add(Line("Kullanım: ca <isim>", LineType.ERROR))
                } else {
                    userName = name
                    saveConfig()
                }
            }
            "cd" -> out.addAll(doCd(args.getOrNull(0)))
            "ls" -> out.addAll(doLs(args))
            "pwd" -> out.add(Line(hidePath(curDir)))
            "mkdir" -> out.addAll(doMkdir(args))
            "touch" -> out.addAll(doTouch(args))
            "rm" -> out.addAll(doRm(args))
            "cp" -> out.addAll(doCp(args))
            "mv" -> out.addAll(doMv(args))
            "cat" -> out.addAll(doCat(args))
            "edit" -> out.addAll(doEditStart(args.getOrNull(0)))
            "whoami" -> out.add(Line(userName))
            "id" -> out.add(Line("uid=1000($userName) gid=1000($userName) groups=1000($userName)"))
            "battery" -> out.add(getBatteryInfo())
            "history" -> out.addAll(doHistory())
            "alias" -> out.addAll(doAlias(args))
            "neofetch" -> out.addAll(neofetch())
            "echo" -> out.add(Line(args.joinToString(" ")))
            "clear" -> out.add(Line("__CLEAR__"))
            "help" -> out.addAll(doHelp(args.getOrNull(0)))
            "exit" -> out.add(Line("__EXIT__"))
            "get" -> out.addAll(doGet(args))
            "" -> {}
            else -> out.add(Line("$cmd: komut bulunamadı", LineType.ERROR))
        }

        return out
    }

    // -----------------------------------------------------
    // cd / ls / pwd
    // -----------------------------------------------------

    private fun doCd(arg: String?): List<Line> {
        val out = mutableListOf<Line>()
        val target: File = when {
            arg.isNullOrBlank() || arg == "~" -> baseDir
            arg.startsWith("/") -> File(baseDir, arg.removePrefix("/"))
            arg.startsWith("~/") -> File(baseDir, arg.removePrefix("~/"))
            else -> File(curDir, arg)
        }
        val resolved = try { target.canonicalFile } catch (e: Exception) { target.absoluteFile }
        val baseCanon = try { baseDir.canonicalFile } catch (e: Exception) { baseDir.absoluteFile }
        val safe = if (resolved == baseCanon || resolved.absolutePath.startsWith(baseCanon.absolutePath + "/")) resolved else baseCanon

        if (!safe.isDirectory) {
            out.add(Line("cd: dizin bulunamadı: $arg", LineType.ERROR))
        } else {
            curDir = safe
        }
        return out
    }

    private fun doLs(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        val flags = args.filter { it.startsWith("-") }.joinToString("")
        val showAll = flags.contains("a")
        val longFormat = flags.contains("l")
        val pathArg = args.firstOrNull { !it.startsWith("-") }
        val target = if (pathArg != null) resolveInBase(pathArg) else curDir

        if (target == null || !target.isDirectory) {
            out.add(Line("ls: dizin bulunamadı", LineType.ERROR))
            return out
        }

        val entries = (target.listFiles() ?: emptyArray())
            .filter { showAll || !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }

        if (entries.isEmpty()) return out

        if (longFormat) {
            entries.forEach { f ->
                val type = if (f.isDirectory) "d" else "-"
                val size = if (f.isDirectory) "-" else humanSize(f.length())
                out.add(Line("$type  ${size.padStart(8)}  ${f.name}"))
            }
        } else {
            out.add(Line(entries.joinToString("  ") { it.name }))
        }
        return out
    }

    // -----------------------------------------------------
    // Dosya işlemleri
    // -----------------------------------------------------

    private fun doMkdir(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        val names = args.filter { !it.startsWith("-") }
        if (names.isEmpty()) {
            out.add(Line("Kullanım: mkdir <dizin>", LineType.ERROR))
            return out
        }
        var ok = true
        for (n in names) {
            val f = resolveInBase(n)
            if (f == null || !f.mkdirs()) ok = false
        }
        out.add(if (ok) Line("Oluşturuldu.", LineType.INFO) else Line("mkdir: oluşturulamadı.", LineType.ERROR))
        return out
    }

    private fun doTouch(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        if (args.isEmpty()) {
            out.add(Line("Kullanım: touch <dosya>", LineType.ERROR))
            return out
        }
        var ok = true
        for (n in args) {
            val f = resolveInBase(n)
            if (f == null) {
                ok = false
                continue
            }
            f.parentFile?.mkdirs()
            if (!f.exists() && !f.createNewFile()) ok = false
            else f.setLastModified(System.currentTimeMillis())
        }
        out.add(if (ok) Line("Oluşturuldu.", LineType.INFO) else Line("touch: oluşturulamadı.", LineType.ERROR))
        return out
    }

    private fun doRm(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        val recursive = args.any { it == "-r" || it == "-rf" || it == "-fr" }
        val names = args.filter { !it.startsWith("-") }
        if (names.isEmpty()) {
            out.add(Line("Kullanım: rm [-r] <dosya/dizin>", LineType.ERROR))
            return out
        }
        var ok = true
        for (n in names) {
            val f = resolveInBase(n)
            if (f == null || !f.exists()) {
                ok = false
                continue
            }
            val success = if (f.isDirectory) {
                if (recursive) f.deleteRecursively() else false
            } else {
                f.delete()
            }
            if (!success) ok = false
        }
        out.add(if (ok) Line("Silindi.", LineType.INFO) else Line("rm: silinemedi (dizin için -r gerekebilir).", LineType.ERROR))
        return out
    }

    private fun doCp(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        val pos = args.filter { !it.startsWith("-") }
        if (pos.size < 2) {
            out.add(Line("Kullanım: cp <kaynak> <hedef>", LineType.ERROR))
            return out
        }
        val src = resolveInBase(pos[0])
        val dest = resolveInBase(pos[1])
        if (src == null || dest == null || !src.exists()) {
            out.add(Line("cp: geçersiz kaynak/hedef", LineType.ERROR))
            return out
        }
        return try {
            val realDest = if (dest.isDirectory) File(dest, src.name) else dest
            src.copyTo(realDest, overwrite = true)
            out.add(Line("Kopyalandı.", LineType.INFO))
            out
        } catch (e: Exception) {
            out.add(Line("cp: kopyalanamadı.", LineType.ERROR))
            out
        }
    }

    private fun doMv(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        val pos = args.filter { !it.startsWith("-") }
        if (pos.size < 2) {
            out.add(Line("Kullanım: mv <kaynak> <hedef>", LineType.ERROR))
            return out
        }
        val src = resolveInBase(pos[0])
        val dest = resolveInBase(pos[1])
        if (src == null || dest == null || !src.exists()) {
            out.add(Line("mv: geçersiz kaynak/hedef", LineType.ERROR))
            return out
        }
        val realDest = if (dest.isDirectory) File(dest, src.name) else dest
        val ok = src.renameTo(realDest)
        out.add(if (ok) Line("Taşındı.", LineType.INFO) else Line("mv: taşınamadı.", LineType.ERROR))
        return out
    }

    private fun doCat(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        val name = args.getOrNull(0)
        if (name == null) {
            out.add(Line("Kullanım: cat <dosya>", LineType.ERROR))
            return out
        }
        val f = resolveInBase(name)
        if (f == null || !f.isFile) {
            out.add(Line("cat: dosya bulunamadı: $name", LineType.ERROR))
            return out
        }
        return try {
            f.readLines().map { Line(it) }.ifEmpty { listOf(Line("")) }
        } catch (e: Exception) {
            listOf(Line("cat: okunamadı: ${e.message}", LineType.ERROR))
        }
    }

    // -----------------------------------------------------
    // Basit editör (:w :wq :q  a <metin>  d N  N <metin>)
    // -----------------------------------------------------

    private fun doEditStart(name: String?): List<Line> {
        val out = mutableListOf<Line>()
        if (name.isNullOrBlank()) {
            out.add(Line("Kullanım: edit <dosya>", LineType.ERROR))
            return out
        }
        val f = resolveInBase(name)
        if (f == null) {
            out.add(Line("edit: izin verilmeyen yol: $name", LineType.ERROR))
            return out
        }
        editTarget = f
        editLines.clear()
        if (f.isFile) editLines.addAll(f.readLines())
        editMode = true

        out.add(Line("Basit editör: :w kaydet, :wq kaydet+çık, :q çık, 'a metin' satır ekle, 'd N' N. satırı sil, 'N metin' N. satırı değiştir", LineType.INFO))
        out.addAll(renderEditBuffer())
        return out
    }

    private fun renderEditBuffer(): List<Line> {
        val out = mutableListOf<Line>()
        out.add(Line("----- ${hidePath(editTarget!!)} -----", LineType.DIM))
        editLines.forEachIndexed { i, l -> out.add(Line("${(i + 1).toString().padStart(3)}| $l")) }
        out.add(Line("-----", LineType.DIM))
        return out
    }

    private fun handleEditInput(line: String): List<Line> {
        val out = mutableListOf<Line>()
        when {
            line == ":q" -> {
                editMode = false
                out.add(Line("Kaydedilmeden çıkıldı.", LineType.INFO))
            }
            line == ":w" || line == ":wq" -> {
                try {
                    editTarget?.parentFile?.mkdirs()
                    editTarget?.writeText(editLines.joinToString("\n") + if (editLines.isNotEmpty()) "\n" else "")
                    out.add(Line("Kaydedildi: ${hidePath(editTarget!!)}", LineType.INFO))
                } catch (e: Exception) {
                    out.add(Line("edit: kaydedilemedi: ${e.message}", LineType.ERROR))
                }
                if (line == ":wq") editMode = false
            }
            line.startsWith("a ") -> {
                editLines.add(line.removePrefix("a "))
                out.addAll(renderEditBuffer())
            }
            line.startsWith("d ") -> {
                val n = line.removePrefix("d ").trim().toIntOrNull()
                if (n != null && n in 1..editLines.size) {
                    editLines.removeAt(n - 1)
                    out.addAll(renderEditBuffer())
                } else {
                    out.add(Line("edit: geçersiz satır numarası", LineType.ERROR))
                }
            }
            line.isBlank() -> { /* no-op */ }
            else -> {
                val idx = line.indexOf(' ')
                val numStr = if (idx == -1) line else line.substring(0, idx)
                val n = numStr.toIntOrNull()
                if (n != null && n in 1..editLines.size) {
                    editLines[n - 1] = if (idx == -1) "" else line.substring(idx + 1)
                    out.addAll(renderEditBuffer())
                } else {
                    out.add(Line("edit: geçersiz komut", LineType.ERROR))
                }
            }
        }
        if (editMode) out.add(Line("edit> ", LineType.DIM))
        return out
    }

    // -----------------------------------------------------
    // whoami / id / battery zaten yukarıda; history / alias
    // -----------------------------------------------------

    private fun doHistory(): List<Line> {
        if (history.isEmpty()) return listOf(Line("Geçmiş boş.", LineType.INFO))
        return history.mapIndexed { i, h -> Line("${(i + 1).toString().padStart(4)}  $h") }
    }

    private fun doAlias(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        if (args.isEmpty()) {
            if (aliases.isEmpty()) {
                out.add(Line("Tanımlı alias yok.", LineType.INFO))
            } else {
                aliases.forEach { (k, v) -> out.add(Line("$k='$v'")) }
            }
            return out
        }
        val rest = args.joinToString(" ")
        val eq = rest.indexOf('=')
        if (eq == -1) {
            out.add(Line("Kullanım: alias isim=komut", LineType.ERROR))
            return out
        }
        val name = rest.substring(0, eq).trim()
        val value = rest.substring(eq + 1).trim().trim('"', '\'')
        if (name.isBlank() || value.isBlank()) {
            out.add(Line("Kullanım: alias isim=komut", LineType.ERROR))
            return out
        }
        aliases[name] = value
        saveConfig()
        out.add(Line("alias eklendi: $name='$value'", LineType.INFO))
        return out
    }

    private fun getBatteryInfo(): Line {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val statusStr = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Şarj oluyor"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Boşalıyor"
            BatteryManager.BATTERY_STATUS_FULL -> "Dolu"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Şarj olmuyor"
            else -> "Bilinmiyor"
        }
        return Line("Pil: ${if (pct >= 0) "$pct%" else "?"}  Durum: $statusStr")
    }

    // -----------------------------------------------------
    // neofetch
    // -----------------------------------------------------

    private fun neofetch(): List<Line> {
        val out = mutableListOf<Line>()

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val usedMem = mi.totalMem - mi.availMem

        val statFs = StatFs(baseDir.path)
        val totalBytes = statFs.totalBytes
        val availBytes = statFs.availableBytes
        val usedBytes = totalBytes - availBytes

        val uptimeMs = SystemClock.elapsedRealtime()
        val uptimeMin = uptimeMs / 60000
        val hours = uptimeMin / 60
        val mins = uptimeMin % 60

        out.add(Line(""))
        out.add(Line("$userName@\u03A3Term", LineType.ACCENT))
        out.add(Line("----------------", LineType.DIM))
        out.add(Line("Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}"))
        out.add(Line("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"))
        out.add(Line("Uptime: ${hours}s ${mins}dk"))
        out.add(Line("Bellek: ${humanSize(usedMem)}/${humanSize(mi.totalMem)}"))
        out.add(Line("Depolama: ${humanSize(usedBytes)}/${humanSize(totalBytes)}"))
        out.add(Line("Dizin: ${hidePath(curDir)}"))
        out.add(Line(""))
        return out
    }

    // -----------------------------------------------------
    // Yardım
    // -----------------------------------------------------

    private fun doHelp(target: String?): List<Line> {
        val descr = linkedMapOf(
            "ca" to "Kullanıcı adı değiştir: ca <isim>",
            "cd" to "Dizin değiştir: cd <dizin>",
            "ls" to "Dizin içeriğini listele (-l, -a)",
            "pwd" to "Bulunduğun dizini göster",
            "mkdir" to "Dizin oluştur: mkdir <isim>",
            "touch" to "Boş dosya oluştur: touch <isim>",
            "rm" to "Dosya/dizin sil: rm [-r] <isim>",
            "cp" to "Kopyala: cp <kaynak> <hedef>",
            "mv" to "Taşı / yeniden adlandır: mv <kaynak> <hedef>",
            "cat" to "Dosya içeriğini göster: cat <dosya>",
            "edit" to "Basit metin editörü: edit <dosya>",
            "get" to "Dosya indir: get -<kaynak> <URL> [--cd DIR] [--forcename AD] [--zip|--unzip]",
            "battery" to "Pil durumunu gösterir",
            "whoami" to "Giriş yapan kullanıcı adını gösterir",
            "id" to "Kullanıcı kimlik bilgisini gösterir",
            "history" to "Komut geçmişini gösterir, !! son komutu tekrar çalıştırır",
            "alias" to "Alias tanımla/listele: alias isim=komut",
            "neofetch" to "Cihaz ve sistem bilgisi gösterir",
            "clear" to "Ekranı temizle",
            "echo" to "Verilen metni yazdırır",
            "exit" to "Uygulamadan çık",
            "help" to "Bu yardım mesajını gösterir: help <komut>"
        )
        val out = mutableListOf<Line>()
        if (target.isNullOrBlank()) {
            descr.toSortedMap().forEach { (k, v) -> out.add(Line(String.format("%-8s %s", k, v))) }
        } else {
            val d = descr[target]
            if (d != null) out.add(Line("$target: $d"))
            else out.add(Line("help: bilinmeyen komut: $target", LineType.ERROR))
        }
        return out
    }

    // -----------------------------------------------------
    // get (indirme)
    // -----------------------------------------------------

    private suspend fun doGet(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        val sourceFlag = args.getOrNull(0)
        var url = args.getOrNull(1)

        if (sourceFlag == null || url == null) {
            out.add(Line("Kullanım: get -<kaynak> <URL> [--cd <DIZIN>] [--forcename <YENI_ISIM>] [--zip|--unzip]", LineType.ERROR))
            return out
        }

        if (sourceFlag.lowercase() == "-github" && url.contains("github.com") && url.contains("/blob/")) {
            url = url.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/")
            out.add(Line("GitHub URL raw formatına çevrildi.", LineType.INFO))
        }

        var destDir = File(baseDir, "Download")
        var forceName: String? = null
        var archiveMode: String? = null

        var i = 2
        while (i < args.size) {
            when (args[i]) {
                "--cd" -> {
                    i++
                    val d = args.getOrNull(i)
                    if (d != null) {
                        val resolved = resolveInBase(d)
                        if (resolved != null) destDir = resolved
                    }
                }
                "--forcename" -> {
                    i++
                    forceName = args.getOrNull(i)
                }
                "--zip" -> archiveMode = "zip"
                "--unzip" -> archiveMode = "unzip"
                else -> out.add(Line("get: bilinmeyen argüman: ${args[i]}", LineType.ERROR))
            }
            i++
        }

        if (!destDir.exists()) destDir.mkdirs()

        val baseName = url.substringAfterLast("/").substringBefore("?").ifBlank { "indirilen_dosya" }
        val finalName = forceName ?: baseName
        val outFile = File(destDir, finalName)

        out.add(Line("İndiriliyor [kaynak: ${sourceFlag.removePrefix("-")}]: $url", LineType.INFO))

        val downloadResult = withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    return@withContext false
                }
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        if (!downloadResult || !outFile.exists()) {
            out.add(Line("get: indirme başarısız oldu.", LineType.ERROR))
            outFile.delete()
            return out
        }

        when (archiveMode) {
            "zip" -> {
                val zipTarget = if (outFile.extension != "zip") {
                    val renamed = File(outFile.parentFile, "${outFile.name}.zip")
                    outFile.renameTo(renamed)
                    renamed
                } else outFile
                out.add(Line("Tamamlandı (zip): ${hidePath(zipTarget)}", LineType.INFO))
            }
            "unzip" -> {
                val ok = withContext(Dispatchers.IO) { unzipFile(outFile, destDir) }
                outFile.delete()
                if (ok) out.add(Line("Tamamlandı (arşivsiz): ${hidePath(destDir)}", LineType.INFO))
                else out.add(Line("get: unzip başarısız oldu, dosya arşivli bırakıldı.", LineType.ERROR))
            }
            else -> out.add(Line("Tamamlandı: ${hidePath(outFile)}", LineType.INFO))
        }

        return out
    }

    private fun unzipFile(zipFile: File, destDir: File): Boolean {
        return try {
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
