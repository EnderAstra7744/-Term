package com.sigmaterm.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class ShellResult {
    data class Output(val text: String) : ShellResult()
    data class Error(val message: String) : ShellResult()
    data class Info(val message: String) : ShellResult()
    object Clear : ShellResult()
    object Exit : ShellResult()
}

/**
 * ΣTerm core shell logic.
 * All file operations are restricted to the device's primary external storage
 * (equivalent of /storage/emulated/0 on most devices).
 */
class SigmaShell {

    var userName: String = "unknown"
        private set

    private lateinit var baseDir: File
    private var currentDir: File = File("")
    private val history = mutableListOf<String>()
    private val aliases = mutableMapOf<String, String>()
    private var appContext: Context? = null

    // Simple edit mode state
    private var editMode = false
    private var editFile: File? = null
    private var editLines = mutableListOf<String>()

    fun init(context: Context) {
        appContext = context.applicationContext
        baseDir = Environment.getExternalStorageDirectory()
        if (!baseDir.exists() || !baseDir.canRead()) {
            baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        }
        currentDir = baseDir
        loadConfig()
    }

    fun displayPath(): String {
        val abs = currentDir.absolutePath
        return when {
            abs == baseDir.absolutePath -> "~"
            abs.startsWith(baseDir.absolutePath) -> {
                val rel = abs.removePrefix(baseDir.absolutePath)
                if (rel.startsWith("/")) "~$rel" else "~/$rel"
            }
            else -> "~"
        }
    }

    fun execute(rawLine: String): ShellResult {
        // Edit mode has its own command handling
        if (editMode) {
            return handleEditCommand(rawLine)
        }

        if (rawLine.isBlank()) return ShellResult.Output("")

        // !! support
        var lineToRun = rawLine
        if (rawLine.trim() == "!!") {
            if (history.isEmpty()) return ShellResult.Error("history: çalıştırılacak önceki komut yok")
            lineToRun = history.last()
        }

        history.add(lineToRun)

        // Alias expansion
        var line = lineToRun
        val firstWord = line.split(Regex("\\s+"), limit = 2).first()
        if (aliases.containsKey(firstWord)) {
            val rest = line.removePrefix(firstWord).trim()
            line = (aliases[firstWord] + " " + rest).trim()
        }

        val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ShellResult.Output("")

        val cmd = parts[0]
        val args = parts.drop(1)

        return when (cmd) {
            "ca" -> cmdCa(args)
            "cd" -> cmdCd(args)
            "ls" -> cmdLs(args)
            "pwd" -> ShellResult.Output(displayPath())
            "mkdir" -> cmdMkdir(args)
            "touch" -> cmdTouch(args)
            "rm" -> cmdRm(args)
            "cp" -> cmdCp(args)
            "mv" -> cmdMv(args)
            "cat" -> cmdCat(args)
            "edit" -> cmdEdit(args)
            "whoami" -> ShellResult.Output(userName)
            "id" -> ShellResult.Output("uid=1000($userName) gid=1000($userName) groups=1000($userName)")
            "battery" -> cmdBattery()
            "get" -> cmdGet(args)
            "clear" -> ShellResult.Clear
            "exit" -> ShellResult.Exit
            "help", "man" -> cmdHelp(args)
            "history" -> cmdHistory()
            "alias" -> cmdAlias(args)
            "neofetch" -> cmdNeofetch()
            "echo" -> ShellResult.Output(args.joinToString(" "))
            else -> ShellResult.Error("$cmd: komut bulunamadı")
        }
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private fun cmdCa(args: List<String>): ShellResult {
        if (args.isEmpty()) return ShellResult.Error("Kullanım: ca <isim>")
        userName = args[0]
        saveConfig()
        return ShellResult.Info("Kullanıcı adı: $userName")
    }

    private fun cmdCd(args: List<String>): ShellResult {
        val targetArg = args.firstOrNull() ?: "~"
        val target = resolvePath(targetArg) ?: return ShellResult.Error("cd: izin verilmeyen yol: $targetArg")
        if (!target.exists() || !target.isDirectory) {
            return ShellResult.Error("cd: dizin bulunamadı: $targetArg")
        }
        currentDir = target
        return ShellResult.Output("")
    }

    private fun cmdLs(args: List<String>): ShellResult {
        val showAll = args.any { it.contains("a") }
        val longFormat = args.any { it.contains("l") }

        val dir = currentDir
        val files = dir.listFiles()?.sortedBy { it.name.lowercase(Locale.getDefault()) }
            ?: return ShellResult.Error("ls: okunamadı")

        val sb = StringBuilder()
        for (f in files) {
            if (!showAll && f.name.startsWith(".")) continue
            if (longFormat) {
                val type = if (f.isDirectory) "d" else "-"
                val size = f.length()
                val date = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(f.lastModified()))
                sb.append(String.format("%s %8d %s %s\n", type, size, date, f.name))
            } else {
                sb.append(f.name)
                if (f.isDirectory) sb.append("/")
                sb.append("  ")
            }
        }
        if (!longFormat && sb.isNotEmpty()) sb.append("\n")
        return ShellResult.Output(sb.toString().trimEnd())
    }

    private fun cmdMkdir(args: List<String>): ShellResult {
        if (args.isEmpty()) return ShellResult.Error("Kullanım: mkdir <dizin>")
        val results = mutableListOf<String>()
        for (arg in args.filter { !it.startsWith("-") }) {
            val target = resolvePath(arg) ?: return ShellResult.Error("mkdir: izin verilmeyen yol: $arg")
            if (target.exists()) {
                results.add("zaten var: $arg")
            } else if (target.mkdirs()) {
                results.add("oluşturuldu: $arg")
            } else {
                return ShellResult.Error("mkdir: oluşturulamadı: $arg")
            }
        }
        return ShellResult.Info(results.joinToString(", "))
    }

    private fun cmdTouch(args: List<String>): ShellResult {
        if (args.isEmpty()) return ShellResult.Error("Kullanım: touch <dosya>")
        for (arg in args) {
            val target = resolvePath(arg) ?: return ShellResult.Error("touch: izin verilmeyen yol: $arg")
            try {
                if (!target.exists()) {
                    target.parentFile?.mkdirs()
                    target.createNewFile()
                } else {
                    target.setLastModified(System.currentTimeMillis())
                }
            } catch (e: Exception) {
                return ShellResult.Error("touch: oluşturulamadı: $arg")
            }
        }
        return ShellResult.Info("Oluşturuldu.")
    }

    private fun cmdRm(args: List<String>): ShellResult {
        if (args.isEmpty()) return ShellResult.Error("Kullanım: rm [-r] <dosya/dizin>")
        val recursive = args.any { it.contains("r") }
        val targets = args.filter { !it.startsWith("-") }
        if (targets.isEmpty()) return ShellResult.Error("Kullanım: rm [-r] <dosya/dizin>")

        for (arg in targets) {
            val target = resolvePath(arg) ?: return ShellResult.Error("rm: izin verilmeyen yol: $arg")
            if (!target.exists()) return ShellResult.Error("rm: bulunamadı: $arg")
            val ok = if (target.isDirectory) {
                if (recursive) target.deleteRecursively() else false
            } else {
                target.delete()
            }
            if (!ok) return ShellResult.Error("rm: silinemedi: $arg")
        }
        return ShellResult.Info("Silindi.")
    }

    private fun cmdCp(args: List<String>): ShellResult {
        if (args.size < 2) return ShellResult.Error("Kullanım: cp <kaynak> <hedef>")
        val destArg = args.last()
        val sources = args.dropLast(1).filter { !it.startsWith("-") }
        val dest = resolvePath(destArg) ?: return ShellResult.Error("cp: izin verilmeyen hedef: $destArg")

        for (srcArg in sources) {
            val src = resolvePath(srcArg) ?: return ShellResult.Error("cp: izin verilmeyen kaynak: $srcArg")
            if (!src.exists()) return ShellResult.Error("cp: kaynak bulunamadı: $srcArg")
            try {
                val realDest = if (dest.isDirectory) File(dest, src.name) else dest
                src.copyRecursively(realDest, overwrite = true)
            } catch (e: Exception) {
                return ShellResult.Error("cp: kopyalanamadı: $srcArg → $destArg")
            }
        }
        return ShellResult.Info("Kopyalandı.")
    }

    private fun cmdMv(args: List<String>): ShellResult {
        if (args.size < 2) return ShellResult.Error("Kullanım: mv <kaynak> <hedef>")
        val destArg = args.last()
        val sources = args.dropLast(1).filter { !it.startsWith("-") }
        val dest = resolvePath(destArg) ?: return ShellResult.Error("mv: izin verilmeyen hedef: $destArg")

        for (srcArg in sources) {
            val src = resolvePath(srcArg) ?: return ShellResult.Error("mv: izin verilmeyen kaynak: $srcArg")
            if (!src.exists()) return ShellResult.Error("mv: kaynak bulunamadı: $srcArg")
            try {
                val realDest = if (dest.isDirectory) File(dest, src.name) else dest
                src.copyRecursively(realDest, overwrite = true)
                src.deleteRecursively()
            } catch (e: Exception) {
                return ShellResult.Error("mv: taşınamadı: $srcArg → $destArg")
            }
        }
        return ShellResult.Info("Taşındı.")
    }

    private fun cmdCat(args: List<String>): ShellResult {
        if (args.isEmpty()) return ShellResult.Error("Kullanım: cat <dosya>")
        val sb = StringBuilder()
        for (arg in args) {
            val target = resolvePath(arg) ?: return ShellResult.Error("cat: izin verilmeyen yol: $arg")
            if (!target.exists() || !target.isFile) return ShellResult.Error("cat: dosya bulunamadı: $arg")
            try {
                sb.append(target.readText())
                if (!sb.endsWith("\n")) sb.append("\n")
            } catch (e: Exception) {
                return ShellResult.Error("cat: okunamadı: $arg")
            }
        }
        return ShellResult.Output(sb.toString())
    }

    private fun cmdEdit(args: List<String>): ShellResult {
        if (args.isEmpty()) return ShellResult.Error("Kullanım: edit <dosya>")
        val target = resolvePath(args[0]) ?: return ShellResult.Error("edit: izin verilmeyen yol: ${args[0]}")

        editFile = target
        editLines = if (target.exists() && target.isFile) {
            try {
                target.readLines().toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
        } else {
            mutableListOf()
        }
        editMode = true

        val sb = StringBuilder()
        sb.append("[*] Basit editör: :w kaydet, :wq kaydet+çık, :q çık\n")
        sb.append("[*] 'a metin' satır ekle | 'd N' N. satırı sil | 'N metin' N. satırı değiştir\n")
        sb.append("----- ${displayPathFor(target)} -----\n")
        editLines.forEachIndexed { i, line ->
            sb.append(String.format("%3d| %s\n", i + 1, line))
        }
        sb.append("-----")
        return ShellResult.Output(sb.toString())
    }

    private fun handleEditCommand(raw: String): ShellResult {
        val line = raw.trim()
        when {
            line == ":q" -> {
                editMode = false
                editFile = null
                editLines.clear()
                return ShellResult.Info("Kaydedilmeden çıkıldı.")
            }
            line == ":w" || line == ":wq" -> {
                val file = editFile ?: return ShellResult.Error("edit: dosya yok")
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(editLines.joinToString("\n") + if (editLines.isNotEmpty()) "\n" else "")
                    val msg = "Kaydedildi: ${displayPathFor(file)}"
                    if (line == ":wq") {
                        editMode = false
                        editFile = null
                        editLines.clear()
                    }
                    return ShellResult.Info(msg)
                } catch (e: Exception) {
                    return ShellResult.Error("edit: kaydedilemedi")
                }
            }
            line.startsWith("a ") -> {
                editLines.add(line.removePrefix("a ").trim())
                return showEditBuffer()
            }
            line.startsWith("d ") -> {
                val n = line.removePrefix("d ").trim().toIntOrNull()
                if (n == null || n < 1 || n > editLines.size) {
                    return ShellResult.Error("edit: geçersiz satır numarası")
                }
                editLines.removeAt(n - 1)
                return showEditBuffer()
            }
            line.matches(Regex("^\\d+\\s+.*")) -> {
                val num = line.substringBefore(" ").toIntOrNull()
                val txt = line.substringAfter(" ")
                if (num == null || num < 1 || num > editLines.size) {
                    return ShellResult.Error("edit: geçersiz satır numarası")
                }
                editLines[num - 1] = txt
                return showEditBuffer()
            }
            line.isEmpty() -> return showEditBuffer()
            else -> return ShellResult.Error("edit: geçersiz komut (:w :wq :q a d N)")
        }
    }

    private fun showEditBuffer(): ShellResult {
        val sb = StringBuilder()
        sb.append("----- ${displayPathFor(editFile!!)} -----\n")
        editLines.forEachIndexed { i, l ->
            sb.append(String.format("%3d| %s\n", i + 1, l))
        }
        sb.append("-----")
        return ShellResult.Output(sb.toString())
    }

    private fun displayPathFor(f: File): String {
        val abs = f.absolutePath
        return when {
            abs == baseDir.absolutePath -> "~"
            abs.startsWith(baseDir.absolutePath) -> {
                val rel = abs.removePrefix(baseDir.absolutePath)
                if (rel.startsWith("/")) "~$rel" else "~/$rel"
            }
            else -> f.name
        }
    }

    private fun cmdBattery(): ShellResult {
        val ctx = appContext ?: return ShellResult.Error("battery: context yok")
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "şarj oluyor"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "deşarj"
                    BatteryManager.BATTERY_STATUS_FULL -> "dolu"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "şarj olmuyor"
                    else -> "bilinmiyor"
                }
            } else "bilinmiyor"

            // Temperature via sticky intent
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.let { it / 10.0 } ?: -1.0

            ShellResult.Output("Pil: $level%  Durum: $status  Sıcaklık: ${if (temp >= 0) "$temp°C" else "?"}")
        } catch (e: Exception) {
            ShellResult.Error("battery: veri alınamadı")
        }
    }

    private fun cmdGet(args: List<String>): ShellResult {
        if (args.size < 2) {
            return ShellResult.Error("Kullanım: get -<kaynak> <URL> [--cd DIR] [--forcename AD]")
        }

        var sourceFlag = args[0]
        var urlStr = args[1]
        var destDir = File(baseDir, "Download")
        var forceName: String? = null

        // GitHub blob → raw conversion
        if (sourceFlag.lowercase().contains("github") && urlStr.contains("github.com") && urlStr.contains("/blob/")) {
            urlStr = urlStr.replace("github.com", "raw.githubusercontent.com")
                .replace("/blob/", "/")
        }

        var i = 2
        while (i < args.size) {
            when (args[i]) {
                "--cd" -> {
                    if (i + 1 < args.size) {
                        val p = resolvePath(args[i + 1])
                        if (p != null) destDir = p
                        i += 2
                    } else i++
                }
                "--forcename" -> {
                    if (i + 1 < args.size) {
                        forceName = args[i + 1]
                        i += 2
                    } else i++
                }
                else -> i++
            }
        }

        destDir.mkdirs()
        if (!destDir.isDirectory) return ShellResult.Error("get: hedef dizin oluşturulamadı")

        val baseName = forceName ?: urlStr.substringBefore("?").substringAfterLast("/").ifEmpty { "indirilen_dosya" }
        val outFile = File(destDir, baseName)

        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.connect()

            if (conn.responseCode !in 200..299) {
                return ShellResult.Error("get: HTTP ${conn.responseCode}")
            }

            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()
            ShellResult.Info("Tamamlandı: ${displayPathFor(outFile)}")
        } catch (e: Exception) {
            outFile.delete()
            ShellResult.Error("get: indirme başarısız – ${e.message}")
        }
    }

    private fun cmdHistory(): ShellResult {
        if (history.isEmpty()) return ShellResult.Info("Geçmiş boş.")
        val sb = StringBuilder()
        history.forEachIndexed { i, cmd ->
            sb.append(String.format("%4d  %s\n", i + 1, cmd))
        }
        return ShellResult.Output(sb.toString())
    }

    private fun cmdAlias(args: List<String>): ShellResult {
        if (args.isEmpty()) {
            if (aliases.isEmpty()) return ShellResult.Info("Tanımlı alias yok.")
            return ShellResult.Output(aliases.entries.joinToString("\n") { "${it.key}='${it.value}'" })
        }
        val joined = args.joinToString(" ")
        if (!joined.contains("=")) return ShellResult.Error("Kullanım: alias isim=komut")
        val name = joined.substringBefore("=").trim()
        val value = joined.substringAfter("=").trim().trim('\'', '"')
        if (name.isEmpty() || value.isEmpty()) return ShellResult.Error("Kullanım: alias isim=komut")
        aliases[name] = value
        saveConfig()
        return ShellResult.Info("alias eklendi: $name='$value'")
    }

    private fun cmdHelp(args: List<String>): ShellResult {
        val helpMap = mapOf(
            "ca" to "Kullanıcı olarak giriş yap: ca <isim>",
            "cd" to "Dizin değiştir: cd <dizin>",
            "ls" to "Dizin içeriğini listele (-l / -a destekler)",
            "pwd" to "Bulunduğun dizini göster",
            "mkdir" to "Dizin oluştur: mkdir <isim>",
            "touch" to "Boş dosya oluştur: touch <isim>",
            "rm" to "Dosya/dizin sil: rm [-r] <isim>",
            "cp" to "Kopyala: cp <kaynak> <hedef>",
            "mv" to "Taşı / yeniden adlandır: mv <kaynak> <hedef>",
            "cat" to "Dosya içeriğini göster: cat <dosya>",
            "edit" to "Basit metin editörü: edit <dosya>",
            "whoami" to "Giriş yapan kullanıcı adını gösterir",
            "id" to "Kullanıcı kimlik bilgisini gösterir",
            "battery" to "Pil durumunu gösterir",
            "get" to "Dosya indir: get -<kaynak> <URL> [--cd DIR] [--forcename AD]",
            "history" to "Komut geçmişini gösterir ( !! son komutu tekrarlar )",
            "alias" to "Alias tanımla/listele: alias isim=komut",
            "neofetch" to "Sistem bilgisi gösterir",
            "clear" to "Ekranı temizle",
            "exit" to "Uygulamadan çık",
            "help" to "Bu yardım mesajını gösterir"
        )
        return if (args.isEmpty()) {
            val sb = StringBuilder()
            helpMap.toSortedMap().forEach { (k, v) ->
                sb.append(String.format("  %-10s %s\n", k, v))
            }
            ShellResult.Output(sb.toString())
        } else {
            val t = args[0]
            val desc = helpMap[t]
            if (desc != null) ShellResult.Output("$t: $desc")
            else ShellResult.Error("help: bilinmeyen komut: $t")
        }
    }

    private fun cmdNeofetch(): ShellResult {
        val os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val kernel = System.getProperty("os.version") ?: "?"
        val cores = Runtime.getRuntime().availableProcessors()

        val memInfo = try {
            val rt = Runtime.getRuntime()
            val used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val max = rt.maxMemory() / (1024 * 1024)
            "${used}MB / ${max}MB"
        } catch (e: Exception) { "?" }

        val diskInfo = try {
            val stat = StatFs(baseDir.absolutePath)
            val total = stat.totalBytes / (1024 * 1024 * 1024)
            val free = stat.availableBytes / (1024 * 1024 * 1024)
            val used = total - free
            "${used}G / ${total}G"
        } catch (e: Exception) { "?" }

        val logo = listOf(
            "  ████████████",
            " ██            ██",
            "██    ΣTerm     ██",
            "██              ██",
            " ██            ██",
            "  ████████████",
            ""
        )

        val info = listOf(
            "$userName@ΣTerm",
            "----------------",
            "OS: $os",
            "Device: $device",
            "Kernel: $kernel",
            "CPU: $cores cores",
            "Memory: $memInfo",
            "Disk: $diskInfo",
            "Dir: ${displayPath()}"
        )

        val sb = StringBuilder("\n")
        val max = maxOf(logo.size, info.size)
        for (i in 0 until max) {
            val left = logo.getOrElse(i) { "" }.padEnd(22)
            val right = info.getOrElse(i) { "" }
            sb.append("$left  $right\n")
        }
        return ShellResult.Output(sb.toString())
    }

    // ------------------------------------------------------------------
    // Path helpers
    // ------------------------------------------------------------------

    private fun resolvePath(arg: String): File? {
        val target = when {
            arg == "~" || arg.isEmpty() -> baseDir
            arg.startsWith("~/") -> File(baseDir, arg.removePrefix("~/"))
            arg.startsWith("/") -> File(baseDir, arg.removePrefix("/"))
            else -> File(currentDir, arg)
        }

        val canonical = try {
            target.canonicalFile
        } catch (e: Exception) {
            return null
        }

        if (!canonical.absolutePath.startsWith(baseDir.canonicalPath)) {
            return null
        }
        return canonical
    }

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    private fun configFile(): File = File(baseDir, ".sigmatermrc")

    private fun loadConfig() {
        val f = configFile()
        if (!f.exists()) return
        try {
            f.readLines().forEach { line ->
                when {
                    line.startsWith("default_user=") -> {
                        userName = line.removePrefix("default_user=").trim()
                    }
                    line.startsWith("alias:") -> {
                        val rest = line.removePrefix("alias:")
                        val name = rest.substringBefore("=")
                        val value = rest.substringAfter("=")
                        if (name.isNotBlank()) aliases[name] = value
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun saveConfig() {
        try {
            val f = configFile()
            f.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append("default_user=$userName\n")
            aliases.forEach { (k, v) -> sb.append("alias:$k=$v\n") }
            f.writeText(sb.toString())
        } catch (_: Exception) { }
    }
}
