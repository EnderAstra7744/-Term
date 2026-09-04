package com.sigmaterm.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Core shell engine that reimplements the original ΣTerm bash script logic
 * in pure Kotlin / Android APIs.
 *
 * Removed (as requested):
 * - proot-distro / arch / distro commands
 * - git / github / gitea passthrough
 */
class ShellEngine(private val context: Context) {

    data class Output(
        val text: String,
        val isError: Boolean = false,
        val isInfo: Boolean = false
    )

    // ---- State ----
    private val baseDir: File = Environment.getExternalStorageDirectory()
    var currentDir: File = baseDir
        private set

    var userName: String = "unknown"
        private set

    private val aliases = mutableMapOf<String, String>()
    private val portals = mutableMapOf<String, String>()
    private val history = mutableListOf<String>()
    private val cmdCounts = mutableMapOf<String, Int>()

    private var themeName: String = "gray"
    private val themeCodes = mapOf(
        "gray" to "0;37",
        "red" to "1;31",
        "green" to "1;32",
        "blue" to "1;34",
        "purple" to "1;35",
        "cyan" to "1;36",
        "yellow" to "1;33",
        "matrix" to "1;32"
    )

    private var locked = false
    private var lockPass: String = ""

    private val sessionStart = System.currentTimeMillis()
    private var commandCount = 0

    private val fortunes = listOf(
        "Kod yazmayan gün, boşa geçmiş gündür.",
        "En iyi hata ayıklayıcı: sabır.",
        "Yavaş ol, ama doğru ol.",
        "Terminal küçük bir dünyadır; sen onun tanrısısın.",
        "Bugün derlemezse, yarın derler.",
        "Ctrl+C her derde deva değildir.",
        "Basit çözüm, en iyi çözümdür.",
        "Yorum satırı yazmayan, geleceğini karartır.",
        "Her 'rm -rf' öncesi bir kez daha düşün.",
        "ΣTerm seninle, korkma.",
        "İyi bir yedek, bin pişmanlığa bedeldir.",
        "Kaos da bir düzendir, anlamak zaman alır."
    )

    private val configFile: File
        get() = File(context.filesDir, ".sigmatermrc")

    private val snapDir: File
        get() = File(context.filesDir, ".sigmaterm_snapshots").also { it.mkdirs() }

    init {
        loadConfig()
        // Ensure base exists and is readable
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        currentDir = baseDir
    }

    // ---- Public API ----

    fun getPrompt(): String {
        val path = hidePath(currentDir)
        return "$userName@ΣTerm:$pathΣ \$ "
    }

    fun execute(rawLine: String): List<Output> {
        val outputs = mutableListOf<Output>()
        var line = rawLine.trim()
        if (line.isEmpty()) return outputs

        // !! history
        if (line == "!!") {
            if (history.isEmpty()) {
                outputs += err("history: çalıştırılacak önceki komut yok")
                return outputs
            }
            line = history.last()
            outputs += info(line)
        }

        history.add(line)
        if (history.size > 500) history.removeAt(0)

        val tokens = tokenize(line)
        if (tokens.isEmpty()) return outputs

        var cmd = tokens[0]
        var args = tokens.drop(1)

        // Alias expansion
        aliases[cmd]?.let { aliasValue ->
            val aliasTokens = tokenize(aliasValue)
            cmd = aliasTokens[0]
            args = aliasTokens.drop(1) + args
        }

        commandCount++
        cmdCounts[cmd] = (cmdCounts[cmd] ?: 0) + 1

        // Lock protection
        if (locked && cmd in listOf("rm", "arch", "distro")) {
            outputs += err("Bu komut kilitli. Önce: unlock <parola>")
            return outputs
        }

        when (cmd) {
            "ca" -> outputs += doCa(args)
            "cd" -> outputs += doCd(args)
            "ls" -> outputs += doLs(args)
            "pwd" -> outputs += doPwd()
            "mkdir" -> outputs += doMkdir(args)
            "touch" -> outputs += doTouch(args)
            "rm" -> outputs += doRm(args)
            "cp" -> outputs += doCp(args)
            "mv" -> outputs += doMv(args)
            "cat" -> outputs += doCat(args)
            "edit" -> outputs += listOf(info("edit: terminal içi editör henüz UI ile bağlanmadı. Dosya yolu: ${args.firstOrNull() ?: "?"}"))
            "whoami" -> outputs += Output(userName)
            "id" -> outputs += Output("uid=1000($userName) gid=1000($userName) groups=1000($userName)")
            "battery" -> outputs += doBattery()
            "history" -> outputs += doHistory()
            "alias" -> outputs += doAlias(args)
            "theme" -> outputs += doTheme(args)
            "fortune", "quote" -> outputs += Output("\"${fortunes[Random.nextInt(fortunes.size)]}\"")
            "ascii" -> outputs += doAscii(args)
            "portal" -> outputs += doPortal(args)
            "snapshot" -> outputs += doSnapshot()
            "restore" -> outputs += doRestore()
            "diskmap" -> outputs += doDiskmap(args)
            "lock" -> outputs += doLock(args)
            "unlock" -> outputs += doUnlock(args)
            "stats" -> outputs += doStats()
            "neofetch" -> outputs += doNeofetch()
            "clear" -> outputs += Output("\u000C") // form feed – UI clears on this
            "echo" -> outputs += Output(args.joinToString(" "))
            "get" -> outputs += doGet(args)
            "help", "man" -> outputs += doHelp(args)
            "exit" -> {
                outputs += doStats()
                outputs += info("Çıkılıyor...")
                // UI will finish activity
            }
            else -> outputs += err("$cmd: komut bulunamadı")
        }
        return outputs
    }

    // ---- Helpers ----

    private fun err(msg: String) = Output("[X] $msg", isError = true)
    private fun info(msg: String) = Output("[*] $msg", isInfo = true)

    private fun tokenize(line: String): List<String> {
        // Simple whitespace split; good enough for this shell
        return line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private fun hidePath(file: File): String {
        val abs = file.absolutePath
        val base = baseDir.absolutePath
        return when {
            abs == base -> "~"
            abs.startsWith(base) -> "~" + abs.removePrefix(base)
            else -> "~"
        }
    }

    private fun normalize(path: String): File {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        val stack = mutableListOf<String>()
        for (p in parts) {
            if (p == "..") {
                if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            } else {
                stack.add(p)
            }
        }
        return File("/" + stack.joinToString("/"))
    }

    private fun resolveInBase(arg: String): File? {
        val target = when {
            arg.startsWith("/") -> File(arg)
            arg.startsWith("~/") -> File(baseDir, arg.removePrefix("~/"))
            arg == "~" -> baseDir
            else -> File(currentDir, arg)
        }
        val normalized = normalize(target.absolutePath)
        val basePath = baseDir.absolutePath
        return if (normalized.absolutePath == basePath ||
            normalized.absolutePath.startsWith(basePath + "/")
        ) {
            normalized
        } else {
            null
        }
    }

    // ---- Config ----

    private fun loadConfig() {
        if (!configFile.exists()) return
        configFile.readLines().forEach { line ->
            when {
                line.startsWith("default_user=") -> userName = line.removePrefix("default_user=")
                line.startsWith("theme=") -> {
                    val t = line.removePrefix("theme=")
                    if (t in themeCodes) themeName = t
                }
                line.startsWith("alias:") -> {
                    val rest = line.removePrefix("alias:")
                    val eq = rest.indexOf('=')
                    if (eq > 0) {
                        aliases[rest.substring(0, eq)] = rest.substring(eq + 1)
                    }
                }
                line.startsWith("portal:") -> {
                    val rest = line.removePrefix("portal:")
                    val eq = rest.indexOf('=')
                    if (eq > 0) {
                        portals[rest.substring(0, eq)] = rest.substring(eq + 1)
                    }
                }
            }
        }
    }

    private fun saveConfig() {
        val sb = StringBuilder()
        sb.appendLine("default_user=$userName")
        sb.appendLine("theme=$themeName")
        aliases.forEach { (k, v) -> sb.appendLine("alias:$k=$v") }
        portals.forEach { (k, v) -> sb.appendLine("portal:$k=$v") }
        configFile.writeText(sb.toString())
    }

    // ---- Commands ----

    private fun doCa(args: List<String>): List<Output> {
        if (args.isEmpty()) return listOf(err("Kullanım: ca <isim>"))
        userName = args[0]
        saveConfig()
        return listOf(info("Kullanıcı: $userName"))
    }

    private fun doCd(args: List<String>): List<Output> {
        val arg = args.firstOrNull() ?: "~"
        val target = resolveInBase(arg) ?: return listOf(err("cd: izin verilmeyen yol: $arg"))
        if (!target.isDirectory) return listOf(err("cd: dizin bulunamadı: $arg"))
        currentDir = target
        return emptyList()
    }

    private fun doLs(args: List<String>): List<Output> {
        val showAll = args.any { it == "-a" || it == "-la" || it == "-al" }
        val long = args.any { it.startsWith("-") && it.contains('l') }
        val dir = currentDir
        val files = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
        if (files.isEmpty()) return listOf(Output(""))

        val lines = mutableListOf<String>()
        for (f in files) {
            if (!showAll && f.name.startsWith(".")) continue
            if (long) {
                val type = if (f.isDirectory) "d" else "-"
                val size = f.length()
                val date = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(f.lastModified()))
                lines += String.format("%s %8d %s %s", type, size, date, f.name)
            } else {
                lines += if (f.isDirectory) "${f.name}/" else f.name
            }
        }
        return listOf(Output(lines.joinToString("\n")))
    }

    private fun doPwd(): List<Output> = listOf(Output(hidePath(currentDir)))

    private fun doMkdir(args: List<String>): List<Output> {
        if (args.isEmpty()) return listOf(err("Kullanım: mkdir <dizin>"))
        val outs = mutableListOf<Output>()
        for (a in args.filter { !it.startsWith("-") }) {
            val p = resolveInBase(a)
            if (p == null) {
                outs += err("mkdir: izin verilmeyen yol: $a")
            } else if (p.mkdirs()) {
                outs += info("Oluşturuldu: ${hidePath(p)}")
            } else {
                outs += err("mkdir: oluşturulamadı: $a")
            }
        }
        return outs
    }

    private fun doTouch(args: List<String>): List<Output> {
        if (args.isEmpty()) return listOf(err("Kullanım: touch <dosya>"))
        val outs = mutableListOf<Output>()
        for (a in args) {
            val p = resolveInBase(a)
            if (p == null) {
                outs += err("touch: izin verilmeyen yol: $a")
            } else {
                try {
                    p.parentFile?.mkdirs()
                    if (p.createNewFile() || p.exists()) {
                        outs += info("Oluşturuldu: ${hidePath(p)}")
                    } else {
                        outs += err("touch: oluşturulamadı: $a")
                    }
                } catch (e: Exception) {
                    outs += err("touch: ${e.message}")
                }
            }
        }
        return outs
    }

    private fun doRm(args: List<String>): List<Output> {
        val recursive = args.any { it == "-r" || it == "-rf" || it == "-fr" }
        val targets = args.filter { !it.startsWith("-") }
        if (targets.isEmpty()) return listOf(err("Kullanım: rm [-r] <dosya/dizin>"))
        val outs = mutableListOf<Output>()
        for (a in targets) {
            val p = resolveInBase(a)
            if (p == null) {
                outs += err("rm: izin verilmeyen yol: $a")
            } else if (!p.exists()) {
                outs += err("rm: bulunamadı: $a")
            } else {
                val ok = if (p.isDirectory) {
                    if (recursive) p.deleteRecursively() else false
                } else {
                    p.delete()
                }
                if (ok) outs += info("Silindi: ${hidePath(p)}")
                else outs += err("rm: silinemedi (dizin için -r kullanın): $a")
            }
        }
        return outs
    }

    private fun doCp(args: List<String>): List<Output> {
        val pos = args.filter { !it.startsWith("-") }
        if (pos.size < 2) return listOf(err("Kullanım: cp <kaynak...> <hedef>"))
        val destRaw = pos.last()
        val sources = pos.dropLast(1)
        val dest = resolveInBase(destRaw) ?: return listOf(err("cp: izin verilmeyen hedef: $destRaw"))
        val outs = mutableListOf<Output>()
        for (s in sources) {
            val src = resolveInBase(s)
            if (src == null) {
                outs += err("cp: izin verilmeyen kaynak: $s")
                continue
            }
            try {
                val target = if (dest.isDirectory) File(dest, src.name) else dest
                src.copyTo(target, overwrite = true)
                outs += info("Kopyalandı: ${hidePath(src)} → ${hidePath(target)}")
            } catch (e: Exception) {
                outs += err("cp: ${e.message}")
            }
        }
        return outs
    }

    private fun doMv(args: List<String>): List<Output> {
        val pos = args.filter { !it.startsWith("-") }
        if (pos.size < 2) return listOf(err("Kullanım: mv <kaynak...> <hedef>"))
        val destRaw = pos.last()
        val sources = pos.dropLast(1)
        val dest = resolveInBase(destRaw) ?: return listOf(err("mv: izin verilmeyen hedef: $destRaw"))
        val outs = mutableListOf<Output>()
        for (s in sources) {
            val src = resolveInBase(s)
            if (src == null) {
                outs += err("mv: izin verilmeyen kaynak: $s")
                continue
            }
            try {
                val target = if (dest.isDirectory) File(dest, src.name) else dest
                src.renameTo(target) || run {
                    src.copyTo(target, overwrite = true)
                    src.deleteRecursively()
                }
                outs += info("Taşındı: ${hidePath(src)} → ${hidePath(target)}")
            } catch (e: Exception) {
                outs += err("mv: ${e.message}")
            }
        }
        return outs
    }

    private fun doCat(args: List<String>): List<Output> {
        if (args.isEmpty()) return listOf(err("Kullanım: cat <dosya>"))
        val outs = mutableListOf<Output>()
        for (a in args) {
            val p = resolveInBase(a)
            if (p == null || !p.isFile) {
                outs += err("cat: dosya bulunamadı: $a")
            } else {
                try {
                    outs += Output(p.readText())
                } catch (e: Exception) {
                    outs += err("cat: ${e.message}")
                }
            }
        }
        return outs
    }

    private fun doBattery(): List<Output> {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val statusIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = when (statusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "şarj oluyor"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "deşarj"
            BatteryManager.BATTERY_STATUS_FULL -> "dolu"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "şarj olmuyor"
            else -> "bilinmiyor"
        }
        val temp = statusIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10f) ?: 0f
        return listOf(Output("Pil: $pct%  Durum: $status  Sıcaklık: ${"%.1f".format(temp)}°C"))
    }

    private fun doHistory(): List<Output> {
        if (history.isEmpty()) return listOf(info("Geçmiş boş."))
        val lines = history.mapIndexed { i, h -> String.format("%4d  %s", i + 1, h) }
        return listOf(Output(lines.joinToString("\n")))
    }

    private fun doAlias(args: List<String>): List<Output> {
        if (args.isEmpty()) {
            if (aliases.isEmpty()) return listOf(info("Tanımlı alias yok."))
            return listOf(Output(aliases.entries.joinToString("\n") { "${it.key}='${it.value}'" }))
        }
        val rest = args.joinToString(" ")
        val eq = rest.indexOf('=')
        if (eq <= 0) return listOf(err("Kullanım: alias isim=komut"))
        val name = rest.substring(0, eq).trim()
        var value = rest.substring(eq + 1).trim()
        value = value.trim('"', '\'')
        if (name.isEmpty() || value.isEmpty()) return listOf(err("Kullanım: alias isim=komut"))
        aliases[name] = value
        saveConfig()
        return listOf(info("alias eklendi: $name='$value'"))
    }

    private fun doTheme(args: List<String>): List<Output> {
        if (args.isEmpty()) {
            return listOf(Output("Mevcut tema: $themeName\nKullanılabilir temalar: ${themeCodes.keys.joinToString(" ")}"))
        }
        val name = args[0]
        if (name !in themeCodes) return listOf(err("theme: bilinmeyen tema: $name"))
        themeName = name
        saveConfig()
        return listOf(info("Tema değiştirildi: $name"))
    }

    // Simple 5-row block font for a few characters
    private val font = mapOf(
        'A' to listOf(" # ", "# #", "###", "# #", "# #"),
        'B' to listOf("## ", "# #", "## ", "# #", "## "),
        'C' to listOf(" ##", "#  ", "#  ", "#  ", " ##"),
        'D' to listOf("## ", "# #", "# #", "# #", "## "),
        'E' to listOf("###", "#  ", "## ", "#  ", "###"),
        'F' to listOf("###", "#  ", "## ", "#  ", "#  "),
        'G' to listOf(" ##", "#  ", "# #", "# #", " ##"),
        'H' to listOf("# #", "# #", "###", "# #", "# #"),
        'I' to listOf("###", " # ", " # ", " # ", "###"),
        'J' to listOf("  #", "  #", "  #", "# #", " # "),
        'K' to listOf("# #", "## ", "#  ", "## ", "# #"),
        'L' to listOf("#  ", "#  ", "#  ", "#  ", "###"),
        'M' to listOf("# #", "###", "###", "# #", "# #"),
        'N' to listOf("# #", "###", "###", "###", "# #"),
        'O' to listOf(" # ", "# #", "# #", "# #", " # "),
        'P' to listOf("## ", "# #", "## ", "#  ", "#  "),
        'Q' to listOf(" # ", "# #", "# #", " # ", "  #"),
        'R' to listOf("## ", "# #", "## ", "# #", "# #"),
        'S' to listOf(" ##", "#  ", " # ", "  #", "## "),
        'T' to listOf("###", " # ", " # ", " # ", " # "),
        'U' to listOf("# #", "# #", "# #", "# #", " # "),
        'V' to listOf("# #", "# #", "# #", "# #", " # "),
        'W' to listOf("# #", "# #", "###", "###", "# #"),
        'X' to listOf("# #", "# #", " # ", "# #", "# #"),
        'Y' to listOf("# #", "# #", " # ", " # ", " # "),
        'Z' to listOf("###", "  #", " # ", "#  ", "###"),
        '0' to listOf(" # ", "# #", "# #", "# #", " # "),
        '1' to listOf(" # ", "## ", " # ", " # ", "###"),
        '2' to listOf("## ", "  #", " # ", "#  ", "###"),
        '3' to listOf("## ", "  #", " # ", "  #", "## "),
        '4' to listOf("# #", "# #", "###", "  #", "  #"),
        '5' to listOf("###", "#  ", "## ", "  #", "## "),
        '6' to listOf(" ##", "#  ", "## ", "# #", " # "),
        '7' to listOf("###", "  #", " # ", " # ", " # "),
        '8' to listOf(" # ", "# #", " # ", "# #", " # "),
        '9' to listOf(" # ", "# #", " ##", "  #", " # "),
        ' ' to listOf("   ", "   ", "   ", "   ", "   "),
        'Σ' to listOf("###", "#  ", " ##", "#  ", "###")
    )

    private fun doAscii(args: List<String>): List<Output> {
        if (args.isEmpty()) return listOf(err("Kullanım: ascii <metin>"))
        val text = args.joinToString(" ").uppercase(Locale.ROOT)
        val rows = Array(5) { StringBuilder() }
        for (ch in text) {
            val glyph = font[ch] ?: font[' ']!!
            for (r in 0..4) {
                rows[r].append(glyph[r]).append(' ')
            }
        }
        return listOf(Output(rows.joinToString("\n") { it.toString() }))
    }

    private fun doPortal(args: List<String>): List<Output> {
        if (args.isEmpty()) {
            if (portals.isEmpty()) return listOf(info("Tanımlı portal yok. Kaydetmek için: portal set <isim>"))
            return listOf(Output(portals.entries.joinToString("\n") { "${it.key} -> ${hidePath(File(it.value))}" }))
        }
        when (args[0]) {
            "set" -> {
                val name = args.getOrNull(1) ?: return listOf(err("Kullanım: portal set <isim>"))
                portals[name] = currentDir.absolutePath
                saveConfig()
                return listOf(info("portal kaydedildi: $name -> ${hidePath(currentDir)}"))
            }
            "del" -> {
                val name = args.getOrNull(1) ?: return listOf(err("portal: bulunamadı"))
                if (name !in portals) return listOf(err("portal: bulunamadı: $name"))
                portals.remove(name)
                saveConfig()
                return listOf(info("portal silindi: $name"))
            }
            else -> {
                val path = portals[args[0]] ?: return listOf(err("portal: bulunamadı: ${args[0]}"))
                val dir = File(path)
                if (!dir.isDirectory) return listOf(err("portal: dizin artık mevcut değil"))
                currentDir = dir
                return listOf(info("Işınlandı: ${args[0]}"))
            }
        }
    }

    private fun doSnapshot(): List<Output> {
        val key = currentDir.absolutePath.replace('/', '_')
        val snap = File(snapDir, "$key.snap")
        val lines = mutableListOf<String>()
        currentDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(currentDir).path
            lines += "${f.length()} $rel"
        }
        lines.sort()
        snap.writeText(lines.joinToString("\n"))
        return listOf(info("Anlık görüntü alındı: ${hidePath(currentDir)} (${lines.size} dosya)"))
    }

    private fun doRestore(): List<Output> {
        val key = currentDir.absolutePath.replace('/', '_')
        val snap = File(snapDir, "$key.snap")
        if (!snap.exists()) return listOf(err("restore: bu dizin için kayıtlı bir snapshot yok. Önce: snapshot"))

        val snapFiles = snap.readLines().map { it.substringAfter(' ') }.toSet()
        val curFiles = currentDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(currentDir).path }.toSet()

        val added = curFiles - snapFiles
        val removed = snapFiles - curFiles

        val sb = StringBuilder()
        sb.appendLine("----- Değişiklik Raporu: ${hidePath(currentDir)} -----")
        if (added.isNotEmpty()) {
            sb.appendLine("Eklenen dosyalar:")
            added.forEach { sb.appendLine("  + $it") }
        }
        if (removed.isNotEmpty()) {
            sb.appendLine("Silinen dosyalar:")
            removed.forEach { sb.appendLine("  - $it") }
        }
        if (added.isEmpty() && removed.isEmpty()) {
            return listOf(info("Değişiklik bulunamadı (dosya listesi aynı)."))
        }
        return listOf(Output(sb.toString()))
    }

    private fun doDiskmap(args: List<String>): List<Output> {
        val target = if (args.isNotEmpty()) resolveInBase(args[0]) else currentDir
        if (target == null || !target.isDirectory) return listOf(err("diskmap: dizin değil"))
        val dirs = target.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (dirs.isEmpty()) return listOf(info("diskmap: alt dizin bulunamadı."))

        val sizes = dirs.map { dir ->
            var size = 0L
            dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
            dir.name to size
        }
        val max = sizes.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1

        val lines = sizes.map { (name, size) ->
            val barLen = ((size * 30) / max).toInt().coerceAtLeast(1)
            val bar = "█".repeat(barLen)
            val human = humanSize(size)
            String.format("%-20s %s %s", name, bar, human)
        }
        return listOf(Output(lines.joinToString("\n")))
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f K".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f M".format(mb)
        return "%.1f G".format(mb / 1024.0)
    }

    private fun doLock(args: List<String>): List<Output> {
        if (args.isEmpty()) return listOf(err("Kullanım: lock <parola>"))
        lockPass = args[0]
        locked = true
        return listOf(info("Terminal kilitlendi. Hassas komutlar için: unlock <parola>"))
    }

    private fun doUnlock(args: List<String>): List<Output> {
        if (!locked) return listOf(info("Zaten kilitli değil."))
        if (args.firstOrNull() == lockPass) {
            locked = false
            return listOf(info("Kilit açıldı."))
        }
        return listOf(err("Yanlış parola."))
    }

    private fun doStats(): List<Output> {
        val dur = System.currentTimeMillis() - sessionStart
        val mins = TimeUnit.MILLISECONDS.toMinutes(dur)
        val secs = TimeUnit.MILLISECONDS.toSeconds(dur) % 60
        val most = cmdCounts.maxByOrNull { it.value }
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("----- Oturum İstatistikleri -----")
        sb.appendLine("Toplam komut: $commandCount")
        sb.appendLine("En çok kullanılan: ${most?.key ?: "-"} (${most?.value ?: 0}x)")
        sb.appendLine("Oturum süresi: ${mins}d ${secs}s")
        sb.appendLine("----------------------------------")
        return listOf(Output(sb.toString()))
    }

    private fun doNeofetch(): List<Output> {
        val os = "Android ${Build.VERSION.RELEASE}"
        val kernel = System.getProperty("os.version") ?: "bilinmiyor"
        val uptime = try {
            val min = TimeUnit.MILLISECONDS.toMinutes(android.os.SystemClock.elapsedRealtime())
            "${min / 60}h ${min % 60}m"
        } catch (e: Exception) {
            "bilinmiyor"
        }
        val cpu = Runtime.getRuntime().availableProcessors().toString()
        val mem = try {
            val rt = Runtime.getRuntime()
            val used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
            val max = rt.maxMemory() / 1024 / 1024
            "${used}M / ${max}M"
        } catch (e: Exception) {
            "bilinmiyor"
        }
        val disk = try {
            val stat = StatFs(baseDir.absolutePath)
            val total = stat.totalBytes / 1024 / 1024 / 1024
            val free = stat.availableBytes / 1024 / 1024 / 1024
            val used = total - free
            "${used}G / ${total}G"
        } catch (e: Exception) {
            "bilinmiyor"
        }

        val logo = listOf(
            "ggggggggggggggg;",
            "4@@@===========\"",
            "  0@@_",
            "    @@@_",
            "     'B@g,",
            "       '@@g",
            "       ,@@@",
            "      +@@\"",
            "    _@@F",
            "  _@@P",
            ",@@W",
            "@@@@@@@@@@@@@@@@     ____________,",
            "                     BBBBBBBBBBBB?",
            ""
        )
        val info = listOf(
            "$userName@ΣTerm",
            "----------------",
            "OS: $os",
            "Kernel: $kernel",
            "Uptime: $uptime",
            "Shell: ΣTerm",
            "CPU Çekirdek: $cpu",
            "Bellek: $mem",
            "Disk: $disk",
            "Dizin: ${hidePath(currentDir)}",
            "Tema: $themeName",
            "",
            ""
        )
        val max = maxOf(logo.size, info.size)
        val lines = (0 until max).map { i ->
            val l = logo.getOrElse(i) { "" }.padEnd(38)
            val r = info.getOrElse(i) { "" }
            "$l  $r"
        }
        return listOf(Output("\n" + lines.joinToString("\n") + "\n"))
    }

    private fun doGet(args: List<String>): List<Output> {
        if (args.size < 2) {
            return listOf(err("Kullanım: get -<kaynak> <URL> [--cd DIR] [--forcename AD]"))
        }
        var urlStr = args[1]
        // GitHub blob → raw
        if (urlStr.contains("github.com") && urlStr.contains("/blob/")) {
            urlStr = urlStr
                .replace("github.com", "raw.githubusercontent.com")
                .replace("/blob/", "/")
        }

        var destDir = File(baseDir, "Download")
        var forceName: String? = null
        var i = 2
        while (i < args.size) {
            when (args[i]) {
                "--cd" -> {
                    i++
                    if (i < args.size) {
                        val p = resolveInBase(args[i])
                        if (p != null) destDir = p
                    }
                }
                "--forcename" -> {
                    i++
                    if (i < args.size) forceName = args[i]
                }
            }
            i++
        }

        destDir.mkdirs()
        val baseName = forceName ?: urlStr.substringAfterLast('/').substringBefore('?').ifEmpty { "indirilen_dosya" }
        val outFile = File(destDir, baseName)

        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) {
                return listOf(err("get: HTTP ${conn.responseCode}"))
            }
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            listOf(info("Tamamlandı: ${hidePath(outFile)}"))
        } catch (e: Exception) {
            listOf(err("get: ${e.message}"))
        }
    }

    private fun doHelp(args: List<String>): List<Output> {
        val descr = mapOf(
            "ca" to "Kullanıcı olarak giriş yap: ca <isim>",
            "cd" to "Dizin değiştir: cd <dizin>",
            "ls" to "Dizin içeriğini listele (-l / -a destekli)",
            "pwd" to "Bulunduğun dizini göster",
            "mkdir" to "Dizin oluştur: mkdir <isim>",
            "touch" to "Boş dosya oluştur: touch <isim>",
            "rm" to "Dosya/dizin sil: rm [-r] <isim>",
            "mv" to "Taşı / yeniden adlandır: mv <kaynak> <hedef>",
            "cp" to "Kopyala: cp <kaynak> <hedef>",
            "cat" to "Dosya içeriğini göster: cat <dosya>",
            "edit" to "Basit metin editörü (geliştirme aşamasında)",
            "whoami" to "Giriş yapan kullanıcı adını gösterir",
            "id" to "Kullanıcı kimlik bilgisini gösterir",
            "battery" to "Pil durumunu gösterir",
            "history" to "Komut geçmişini gösterir, !! son komutu tekrar çalıştırır",
            "alias" to "Alias tanımla/listele: alias isim=komut",
            "theme" to "Renk temasını değiştir: theme <isim>",
            "fortune" to "Rastgele bir söz gösterir",
            "ascii" to "Girilen metni büyük ASCII harflerle basar",
            "portal" to "Dizin kısayolu: portal set <isim> / portal <isim>",
            "snapshot" to "Bulunduğun dizinin dosya listesini kaydeder",
            "restore" to "snapshot ile şu anki durumu karşılaştırır",
            "diskmap" to "Alt klasörlerin boyutunu bar-chart olarak gösterir",
            "lock" to "Terminali parola ile kilitler: lock <parola>",
            "unlock" to "Kilidi açar: unlock <parola>",
            "stats" to "O anki oturum istatistiklerini gösterir",
            "neofetch" to "Sistem bilgisi ve logo gösterir",
            "get" to "Dosya indir: get -<kaynak> <URL> [--cd DIR] [--forcename AD]",
            "clear" to "Ekranı temizle",
            "echo" to "Metni yazdır",
            "exit" to "Terminalden çık ve oturum istatistiklerini göster",
            "help" to "Bu yardım mesajını gösterir: help <komut>"
        )
        if (args.isEmpty()) {
            val lines = descr.entries.sortedBy { it.key }.map { String.format("  %-10s %s", it.key, it.value) }
            return listOf(Output(lines.joinToString("\n")))
        }
        val t = args[0]
        return if (t in descr) listOf(Output("$t: ${descr[t]}"))
        else listOf(err("help: bilinmeyen komut: $t"))
    }
}
