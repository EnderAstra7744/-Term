package com.sigmaterm.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.random.Random

enum class LineType { NORMAL, ERROR, INFO, ACCENT, DIM, ECHO }

data class Line(
    val text: String,
    val type: LineType = LineType.NORMAL,
    val paletteColors: List<Int>? = null,
    val rightText: String? = null,
    val rightColor: Int? = null
)

data class FunctionDef(val name: String, val params: List<String>, val body: List<String>)

/**
 * ΣTerm's command engine. Kotlin/native Android counterpart of the
 * original Termux/bash script, extended with a small scripting layer
 * (variables, functions, if/else, for/while, break, or) on top of the
 * classic shell-style commands.
 *
 * Removed on purpose (not meaningful in a native Android app):
 *  - arch / distro (proot-distro based Arch Linux install)
 *  - git / github / gitea (passthrough to real system commands)
 */
class TerminalEngine(private val context: Context) {

    // -----------------------------------------------------
    // Storage
    // -----------------------------------------------------

    var baseDir: File = context.getExternalFilesDir(null) ?: context.filesDir
        private set
    var curDir: File = baseDir
        private set

    var hasFullStorageAccess: Boolean = false
        private set

    /** Called from MainActivity after the storage permission flow completes. */
    fun refreshStorageAccess(): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (granted && !hasFullStorageAccess) {
            hasFullStorageAccess = true
            baseDir = Environment.getExternalStorageDirectory()
            curDir = baseDir
        }
        return hasFullStorageAccess
    }

    var userName: String = "unknown"
        private set

    val history = mutableListOf<String>()
    val aliases = linkedMapOf<String, String>()

    // Scripting state
    val variables = linkedMapOf<String, String>()
    val functions = linkedMapOf<String, FunctionDef>()
    private var lastIfResult: Boolean? = null
    @Volatile var breakRequested: Boolean = false

    private var defineMode = false
    private var defineFuncName: String? = null
    private var defineFuncParams = mutableListOf<String>()
    private var defineBodyLines = mutableListOf<String>()

    // Simple line editor state
    private var editMode = false
    private var editTarget: File? = null
    private val editLines = mutableListOf<String>()

    private val prefs = context.getSharedPreferences("sigmaterm_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val STGK_INDEX_URL =
            "https://raw.githubusercontent.com/EnderAstra7744/sigma-stgk-sage/main/packages.json"

        // Fill this in yourself (a fine-grained, read-only, single-repo token)
        // before building if you want the "Automatic Token" option to work.
        // WARNING: anything placed here is visible to anyone who decompiles
        // the APK. Never commit a real token to a public repository. If this
        // is left blank, "Automatic Token" will simply report that no token
        // is configured and fall back to unauthenticated requests.
        private const val BUILTIN_GITHUB_TOKEN = ""
    }

    var githubToken: String? = null
        private set
    var githubTokenSource: String = "none"
        private set

    fun setManualToken(token: String) {
        githubToken = token.trim().ifBlank { null }
        githubTokenSource = if (githubToken != null) "manual" else "none"
        saveTokenConfig()
    }

    /** Returns true if a non-blank built-in token was actually available. */
    fun useAutomaticToken(): Boolean {
        githubToken = BUILTIN_GITHUB_TOKEN.ifBlank { null }
        githubTokenSource = if (githubToken != null) "automatic" else "none"
        saveTokenConfig()
        return githubToken != null
    }

    fun clearToken() {
        githubToken = null
        githubTokenSource = "none"
        saveTokenConfig()
    }

    fun tokenStatusText(): String = when (githubTokenSource) {
        "manual" -> "Manual token set"
        "automatic" -> "Automatic (built-in) token set"
        else -> "No token configured"
    }

    private fun authHeaderValue(): String? = githubToken?.let { "token $it" }

    private fun saveTokenConfig() {
        prefs.edit()
            .putString("github_token", githubToken ?: "")
            .putString("github_token_source", githubTokenSource)
            .apply()
    }

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
        githubToken = prefs.getString("github_token", "")?.ifBlank { null }
        githubTokenSource = prefs.getString("github_token_source", "none") ?: "none"
    }

    private fun saveConfig() {
        prefs.edit()
            .putString("user_name", userName)
            .putString("aliases", aliases.entries.joinToString("\n") { "${it.key}=${it.value}" })
            .apply()
    }

    // -----------------------------------------------------
    // Helpers
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

    /** Strips one or more layers of [ ... ] / { ... } wrapping around a token. */
    private fun unwrap(s: String): String {
        var r = s.trim()
        while ((r.startsWith("[") && r.endsWith("]")) || (r.startsWith("{") && r.endsWith("}"))) {
            if (r.length < 2) break
            r = r.substring(1, r.length - 1).trim()
        }
        return r
    }

    /** Replaces every occurrence of `$`NAME with the variable's value. */
    private fun substituteVars(s: String): String {
        val regex = Regex("\\$`([A-Za-z_][A-Za-z0-9_]*)")
        return regex.replace(s) { m ->
            variables[m.groupValues[1]] ?: m.value
        }
    }

    /** Resolves a bare token: variable reference (via $`NAME or exact variable name) or literal. */
    private fun resolveExpr(raw: String): String {
        val s = substituteVars(unwrap(raw))
        return variables[s] ?: s
    }

    // -----------------------------------------------------
    // Main entry point
    // -----------------------------------------------------

    suspend fun process(rawInput: String, recordHistory: Boolean = true): List<Line> {
        val out = mutableListOf<Line>()
        var input = rawInput

        // Function definition capture mode
        if (defineMode) {
            if (input.trim() == "end") {
                val name = defineFuncName!!
                functions[name] = FunctionDef(name, defineFuncParams.toList(), defineBodyLines.toList())
                defineMode = false
                defineFuncName = null
                defineFuncParams = mutableListOf()
                defineBodyLines = mutableListOf()
                return listOf(Line("Function '$name' defined.", LineType.INFO))
            } else {
                defineBodyLines.add(input)
                return listOf(Line("  > $input", LineType.DIM))
            }
        }

        if (editMode) {
            return handleEditInput(input)
        }

        if (recordHistory && input == "!!") {
            if (history.isEmpty()) {
                out.add(Line("history: no previous command to run", LineType.ERROR))
                return out
            }
            input = history.last()
            out.add(Line(input, LineType.ECHO))
        }

        if (input.isBlank()) return out

        var parts = input.trim().split(Regex("\\s+"))
        var cmd = parts.getOrElse(0) { "" }

        if (recordHistory) {
            aliases[cmd]?.let { aliasValue ->
                val aliasParts = aliasValue.trim().split(Regex("\\s+"))
                parts = aliasParts + parts.drop(1)
                cmd = parts.getOrElse(0) { "" }
            }
            history.add(input)
        }

        val args = parts.drop(1)

        when (cmd) {
            "ca" -> {
                val name = args.getOrNull(0)
                if (name.isNullOrBlank()) {
                    out.add(Line("Usage: ca <name>", LineType.ERROR))
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
            "echo" -> out.add(Line(substituteVars(args.joinToString(" "))))
            "clear" -> out.add(Line("__CLEAR__"))
            "help" -> out.addAll(doHelp(args.getOrNull(0)))
            "exit" -> out.add(Line("__EXIT__"))
            "get" -> out.addAll(doGet(args))
            "sigmaterm-storage-access" -> out.add(Line("__REQUEST_STORAGE__"))
            "sigmaterm-change-token" -> out.add(Line("__SHOW_TOKEN_MENU__"))

            // ---- Scripting layer ----
            "variables" -> out.addAll(doVariables(args))
            "set" -> out.addAll(doSet(args))
            "setname" -> out.addAll(doSetName(args))
            "unset" -> out.addAll(doUnset(args))
            "env" -> out.addAll(doEnv())
            "function" -> out.addAll(doFunctionStart(args))
            "return" -> { /* only meaningful inside a function body; no-op at top level */
                out.add(Line("return: only valid inside a function body", LineType.ERROR))
            }
            "if" -> out.addAll(doIf(args))
            "else" -> out.addAll(doElse(args))
            "for" -> out.addAll(doFor(args))
            "while" -> out.addAll(doWhile(args))
            "break" -> out.addAll(doBreak(args))
            "or" -> out.addAll(doOr(args))

            // ---- Utilities ----
            "hash" -> out.addAll(doHash(args))
            "chmod" -> out.addAll(doChmod(args))
            "siterm" -> out.addAll(doSiterm(args))
            "requ" -> out.addAll(doRequ(args))
            "stgk" -> out.addAll(doStgk(args))

            "" -> {}
            else -> {
                if (functions.containsKey(cmd)) {
                    out.addAll(callFunction(cmd, args))
                } else {
                    out.add(Line("$cmd: command not found", LineType.ERROR))
                }
            }
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
            out.add(Line("cd: no such directory: $arg", LineType.ERROR))
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
            out.add(Line("ls: no such directory", LineType.ERROR))
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
    // File operations
    // -----------------------------------------------------

    private fun doMkdir(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        val names = args.filter { !it.startsWith("-") }
        if (names.isEmpty()) {
            out.add(Line("Usage: mkdir <dir>", LineType.ERROR))
            return out
        }
        var ok = true
        for (n in names) {
            val f = resolveInBase(n)
            if (f == null || !f.mkdirs()) ok = false
        }
        out.add(if (ok) Line("Created.", LineType.INFO) else Line("mkdir: could not create directory.", LineType.ERROR))
        return out
    }

    private fun doTouch(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        if (args.isEmpty()) {
            out.add(Line("Usage: touch <file>", LineType.ERROR))
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
        out.add(if (ok) Line("Created.", LineType.INFO) else Line("touch: could not create file.", LineType.ERROR))
        return out
    }

    private fun doRm(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        val recursive = args.any { it == "-r" || it == "-rf" || it == "-fr" }
        val names = args.filter { !it.startsWith("-") }
        if (names.isEmpty()) {
            out.add(Line("Usage: rm [-r] <file/dir>", LineType.ERROR))
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
        out.add(if (ok) Line("Deleted.", LineType.INFO) else Line("rm: could not delete (directories need -r).", LineType.ERROR))
        return out
    }

    private fun doCp(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        val pos = args.filter { !it.startsWith("-") }
        if (pos.size < 2) {
            out.add(Line("Usage: cp <source> <dest>", LineType.ERROR))
            return out
        }
        val src = resolveInBase(pos[0])
        val dest = resolveInBase(pos[1])
        if (src == null || dest == null || !src.exists()) {
            out.add(Line("cp: invalid source/destination", LineType.ERROR))
            return out
        }
        return try {
            val realDest = if (dest.isDirectory) File(dest, src.name) else dest
            src.copyTo(realDest, overwrite = true)
            out.add(Line("Copied.", LineType.INFO))
            out
        } catch (e: Exception) {
            out.add(Line("cp: copy failed.", LineType.ERROR))
            out
        }
    }

    private fun doMv(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        val pos = args.filter { !it.startsWith("-") }
        if (pos.size < 2) {
            out.add(Line("Usage: mv <source> <dest>", LineType.ERROR))
            return out
        }
        val src = resolveInBase(pos[0])
        val dest = resolveInBase(pos[1])
        if (src == null || dest == null || !src.exists()) {
            out.add(Line("mv: invalid source/destination", LineType.ERROR))
            return out
        }
        val realDest = if (dest.isDirectory) File(dest, src.name) else dest
        val ok = src.renameTo(realDest)
        out.add(if (ok) Line("Moved.", LineType.INFO) else Line("mv: move failed.", LineType.ERROR))
        return out
    }

    private fun doCat(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        val name = args.getOrNull(0)
        if (name == null) {
            out.add(Line("Usage: cat <file>", LineType.ERROR))
            return out
        }
        val f = resolveInBase(name)
        if (f == null || !f.isFile) {
            out.add(Line("cat: file not found: $name", LineType.ERROR))
            return out
        }
        return try {
            f.readLines().map { Line(it) }.ifEmpty { listOf(Line("")) }
        } catch (e: Exception) {
            listOf(Line("cat: could not read: ${e.message}", LineType.ERROR))
        }
    }

    // -----------------------------------------------------
    // Simple line editor (:w :wq :q  a <text>  d N  N <text>)
    // -----------------------------------------------------

    private fun doEditStart(name: String?): List<Line> {
        val out = mutableListOf<Line>()
        if (name.isNullOrBlank()) {
            out.add(Line("Usage: edit <file>", LineType.ERROR))
            return out
        }
        val f = resolveInBase(name)
        if (f == null) {
            out.add(Line("edit: path not allowed: $name", LineType.ERROR))
            return out
        }
        editTarget = f
        editLines.clear()
        if (f.isFile) editLines.addAll(f.readLines())
        editMode = true

        out.add(Line("Simple editor: :w save, :wq save+quit, :q quit, 'a text' append line, 'd N' delete line N, 'N text' replace line N", LineType.INFO))
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
                out.add(Line("Closed without saving.", LineType.INFO))
            }
            line == ":w" || line == ":wq" -> {
                try {
                    editTarget?.parentFile?.mkdirs()
                    editTarget?.writeText(editLines.joinToString("\n") + if (editLines.isNotEmpty()) "\n" else "")
                    out.add(Line("Saved: ${hidePath(editTarget!!)}", LineType.INFO))
                } catch (e: Exception) {
                    out.add(Line("edit: save failed: ${e.message}", LineType.ERROR))
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
                    out.add(Line("edit: invalid line number", LineType.ERROR))
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
                    out.add(Line("edit: invalid command", LineType.ERROR))
                }
            }
        }
        if (editMode) out.add(Line("edit> ", LineType.DIM))
        return out
    }

    // -----------------------------------------------------
    // history / alias / battery
    // -----------------------------------------------------

    private fun doHistory(): List<Line> {
        if (history.isEmpty()) return listOf(Line("History is empty.", LineType.INFO))
        return history.mapIndexed { i, h -> Line("${(i + 1).toString().padStart(4)}  $h") }
    }

    private fun doAlias(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        if (args.isEmpty()) {
            if (aliases.isEmpty()) {
                out.add(Line("No aliases defined.", LineType.INFO))
            } else {
                aliases.forEach { (k, v) -> out.add(Line("$k='$v'")) }
            }
            return out
        }
        val rest = args.joinToString(" ")
        val eq = rest.indexOf('=')
        if (eq == -1) {
            out.add(Line("Usage: alias name=command", LineType.ERROR))
            return out
        }
        val name = rest.substring(0, eq).trim()
        val value = rest.substring(eq + 1).trim().trim('"', '\'')
        if (name.isBlank() || value.isBlank()) {
            out.add(Line("Usage: alias name=command", LineType.ERROR))
            return out
        }
        aliases[name] = value
        saveConfig()
        out.add(Line("Alias added: $name='$value'", LineType.INFO))
        return out
    }

    private fun getBatteryInfo(): Line {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val statusStr = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }
        return Line("Battery: ${if (pct >= 0) "$pct%" else "?"}  Status: $statusStr")
    }

    // -----------------------------------------------------
    // Variables / scripting primitives
    // -----------------------------------------------------

    private fun doVariables(args: List<String>): List<Line> {
        val eqIdx = args.indexOf("=:")
        if (args.isEmpty() || eqIdx == -1 || eqIdx == 0) {
            return listOf(Line("Usage: variables NAME =: VALUE [-y]", LineType.ERROR))
        }
        val name = args[0]
        val value = args.subList(eqIdx + 1, args.size).filter { it != "-y" }.joinToString(" ")
        variables[name] = value
        return listOf(Line("Variable set: $name = '$value'", LineType.INFO))
    }

    private fun doSet(args: List<String>): List<Line> {
        val eqIdx = args.indexOf("=:")
        if (args.isEmpty() || eqIdx == -1 || eqIdx == 0) {
            return listOf(Line("Usage: set NAME =: VALUE", LineType.ERROR))
        }
        val name = args[0]
        if (!variables.containsKey(name)) {
            return listOf(Line("set: variable not found: $name (use 'variables' to create it)", LineType.ERROR))
        }
        val value = args.subList(eqIdx + 1, args.size).filter { it != "-y" }.joinToString(" ")
        variables[name] = value
        return listOf(Line("Variable updated: $name = '$value'", LineType.INFO))
    }

    private fun doSetName(args: List<String>): List<Line> {
        val eqIdx = args.indexOf("=:")
        if (args.size < 3 || eqIdx != 1) {
            return listOf(Line("Usage: setname OLD_NAME =: NEW_NAME", LineType.ERROR))
        }
        val old = args[0]
        val new = args[2]
        val value = variables.remove(old)
            ?: return listOf(Line("setname: variable not found: $old", LineType.ERROR))
        variables[new] = value
        return listOf(Line("Variable renamed: $old -> $new", LineType.INFO))
    }

    private fun doUnset(args: List<String>): List<Line> {
        val force = args.contains("-fv")
        val names = args.filter { it != "-fv" && it != "-y" }
        if (names.isEmpty()) {
            return listOf(Line("Usage: unset [-fv] NAME... [-y]", LineType.ERROR))
        }
        val out = mutableListOf<Line>()
        for (n in names) {
            if (variables.remove(n) != null) {
                out.add(Line("Removed: $n", LineType.INFO))
            } else if (!force) {
                out.add(Line("unset: variable not found: $n", LineType.ERROR))
            }
        }
        return out
    }

    private fun doEnv(): List<Line> {
        if (variables.isEmpty()) return listOf(Line("No variables defined.", LineType.INFO))
        return variables.entries.mapIndexed { i, e -> Line("[${i + 1}] ${e.key} -> ${e.value}") }
    }

    // -----------------------------------------------------
    // Functions
    // -----------------------------------------------------

    private fun doFunctionStart(args: List<String>): List<Line> {
        val doIdx = args.indexOf("do:")
        if (args.isEmpty() || doIdx == -1) {
            return listOf(Line("Usage: function NAME [{param1,param2}] do:", LineType.ERROR))
        }
        val name = args[0]
        val params = if (doIdx > 1) unwrap(args[1]).split(",").map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

        defineMode = true
        defineFuncName = name
        defineFuncParams = params.toMutableList()
        defineBodyLines = mutableListOf()

        return listOf(Line("Defining function '$name'... type 'end' on its own line to finish.", LineType.INFO))
    }

    private suspend fun callFunction(name: String, args: List<String>): List<Line> {
        val fn = functions[name] ?: return listOf(Line("$name: command not found", LineType.ERROR))
        val out = mutableListOf<Line>()

        if (fn.params.isNotEmpty()) {
            fn.params.forEachIndexed { i, p -> variables[p] = args.getOrElse(i) { "" } }
        } else if (args.isNotEmpty()) {
            variables["IT"] = args[0]
        }

        for (bodyLine in fn.body) {
            if (breakRequested) break
            val trimmed = bodyLine.trim()
            if (trimmed.startsWith("return")) {
                val value = resolveExpr(trimmed.removePrefix("return").trim())
                variables["RETURN"] = value
                out.add(Line(value))
                break
            }
            out.addAll(process(substituteVars(bodyLine), recordHistory = false))
            yield()
        }
        return out
    }

    // -----------------------------------------------------
    // if / else / for / while / break / or
    // -----------------------------------------------------

    private fun evalCondition(e1: String, op: String, e2: String): Boolean {
        val n1 = e1.toDoubleOrNull()
        val n2 = e2.toDoubleOrNull()
        return if (n1 != null && n2 != null) {
            when (op) {
                "==" -> n1 == n2
                "!=" -> n1 != n2
                ">" -> n1 > n2
                "<" -> n1 < n2
                ">=" -> n1 >= n2
                "<=" -> n1 <= n2
                else -> false
            }
        } else {
            when (op) {
                "==" -> e1 == e2
                "!=" -> e1 != e2
                ">" -> e1 > e2
                "<" -> e1 < e2
                ">=" -> e1 >= e2
                "<=" -> e1 <= e2
                else -> false
            }
        }
    }

    private suspend fun doIf(args: List<String>): List<Line> {
        val doIdx = args.indexOf("do:")
        if (doIdx != 3 || args.size < 5) {
            return listOf(Line("Usage: if {exp1} {OPERATOR} {exp2} do: {function}", LineType.ERROR))
        }
        val e1 = resolveExpr(args[0])
        val op = unwrap(args[1])
        val e2 = resolveExpr(args[2])
        val result = evalCondition(e1, op, e2)
        lastIfResult = result
        return if (result) {
            callFunction(unwrap(args[doIdx + 1]), emptyList())
        } else {
            listOf(Line("(condition is false)", LineType.DIM))
        }
    }

    private suspend fun doElse(args: List<String>): List<Line> {
        val doIdx = args.indexOf("do:")
        if (doIdx == -1 || args.size < doIdx + 2) {
            return listOf(Line("Usage: else do: {function}", LineType.ERROR))
        }
        val result = lastIfResult
            ?: return listOf(Line("else: no preceding 'if'", LineType.ERROR))
        lastIfResult = null
        return if (!result) {
            callFunction(unwrap(args[doIdx + 1]), emptyList())
        } else {
            listOf(Line("(skipped, previous if was true)", LineType.DIM))
        }
    }

    private suspend fun doFor(args: List<String>): List<Line> {
        val doIdx = args.indexOf("do:")
        if (doIdx == -1 || args.size < doIdx + 2) {
            return listOf(Line("Usage: for {exp1,exp2,exp3} do: {function}", LineType.ERROR))
        }
        val listToken = unwrap(args[0])
        val funcName = unwrap(args[doIdx + 1])

        val items: List<String> = if (Regex("^-?\\d+-\\d+$").matches(listToken)) {
            val (a, b) = listToken.split("-", limit = 2).map { it.toInt() }
            if (a <= b) (a..b).map { it.toString() } else (a downTo b).map { it.toString() }
        } else {
            listToken.split(",").map { resolveExpr(it) }
        }

        val out = mutableListOf<Line>()
        breakRequested = false
        for (item in items) {
            if (breakRequested) {
                out.add(Line("for: loop stopped (break)", LineType.INFO))
                break
            }
            out.addAll(callFunction(funcName, listOf(item)))
            yield()
        }
        breakRequested = false
        return out
    }

    private fun evalWhileCondition(token: String): Boolean {
        val opMatch = Regex("(==|!=|>=|<=|>|<)").find(token)
        if (opMatch != null) {
            val op = opMatch.value
            val parts = token.split(op, limit = 2)
            if (parts.size == 2) {
                return evalCondition(resolveExpr(parts[0]), op, resolveExpr(parts[1]))
            }
        }
        val v = resolveExpr(token)
        return v.equals("true", ignoreCase = true) || (v.toDoubleOrNull() ?: 0.0) != 0.0
    }

    private suspend fun doWhile(args: List<String>): List<Line> {
        val doIdx = args.indexOf("do:")
        if (doIdx == -1 || args.size < doIdx + 2) {
            return listOf(Line("Usage: while {condition} do: {function}", LineType.ERROR))
        }
        val condToken = unwrap(args[0])
        val funcName = unwrap(args[doIdx + 1])

        val out = mutableListOf<Line>()
        breakRequested = false
        var iterations = 0
        val maxIterations = 100_000

        while (evalWhileCondition(condToken) && iterations < maxIterations) {
            if (breakRequested) {
                out.add(Line("while: loop stopped (break)", LineType.INFO))
                break
            }
            out.addAll(callFunction(funcName, emptyList()))
            iterations++
            yield()
        }
        if (iterations >= maxIterations) {
            out.add(Line("while: stopped after safety limit ($maxIterations iterations)", LineType.ERROR))
        }
        breakRequested = false
        return out
    }

    private fun doBreak(args: List<String>): List<Line> {
        breakRequested = true
        return listOf(Line("Break signal sent to the running loop.", LineType.INFO))
    }

    private suspend fun doOr(args: List<String>): List<Line> {
        val percentIdx = args.indexOfFirst { it.startsWith("%") }
        val doIdx = args.indexOf("do:")
        if (percentIdx == -1 || doIdx == -1 || args.size < doIdx + 2) {
            return listOf(Line("Usage: or %<0-100> {exp1,exp2} do: {func1,func2}", LineType.ERROR))
        }
        val percent = args[percentIdx].removePrefix("%").toIntOrNull()?.coerceIn(0, 100) ?: 50
        val expsToken = if (percentIdx + 1 < args.size) unwrap(args[percentIdx + 1]) else ""
        val funcsToken = unwrap(args[doIdx + 1])

        val exps = expsToken.split(",").map { it.trim() }
        val funcs = funcsToken.split(",").map { it.trim() }

        val roll = Random.nextInt(100)
        val chooseFirst = roll < percent
        val chosenFunc = if (chooseFirst) funcs.getOrNull(0) else funcs.getOrNull(1)
        val chosenArg = if (chooseFirst) exps.getOrNull(0) else exps.getOrNull(1)

        if (chosenFunc.isNullOrBlank()) {
            return listOf(Line("or: no function to call for the chosen branch", LineType.ERROR))
        }
        return callFunction(chosenFunc, listOfNotNull(chosenArg))
    }

    // -----------------------------------------------------
    // hash / chmod / siterm / requ
    // -----------------------------------------------------

    private fun doHash(args: List<String>): List<Line> {
        val algoRaw = args.getOrNull(0)?.lowercase()
        val text = args.drop(1).joinToString(" ")
        if (algoRaw == null || text.isBlank()) {
            return listOf(Line("Usage: hash <md5|sha1|sha256|sha512> <text>", LineType.ERROR))
        }
        val algo = when (algoRaw) {
            "md5" -> "MD5"
            "sha1" -> "SHA-1"
            "sha256", "sha264" -> "SHA-256"
            "sha512" -> "SHA-512"
            else -> null
        }
        if (algo == null) {
            return listOf(Line("hash: unsupported algorithm '$algoRaw' (supported: md5, sha1, sha256, sha512)", LineType.ERROR))
        }
        val digest = MessageDigest.getInstance(algo).digest(text.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return listOf(Line(hex))
    }

    private fun doChmod(args: List<String>): List<Line> {
        val mode = args.getOrNull(0)
        val pathArg = args.getOrNull(1)
        if (mode == null || pathArg == null) {
            return listOf(Line("Usage: chmod <+x|-x|+w|-w|+r|-r|OWNER_OCTAL_DIGIT> <path>", LineType.ERROR))
        }
        val f = resolveInBase(pathArg)
        if (f == null || !f.exists()) {
            return listOf(Line("chmod: file not found: $pathArg", LineType.ERROR))
        }

        var applied = false
        when (mode) {
            "+x" -> { f.setExecutable(true); applied = true }
            "-x" -> { f.setExecutable(false); applied = true }
            "+w" -> { f.setWritable(true); applied = true }
            "-w" -> { f.setWritable(false); applied = true }
            "+r" -> { f.setReadable(true); applied = true }
            "-r" -> { f.setReadable(false); applied = true }
            else -> {
                val digit = mode.lastOrNull()?.digitToIntOrNull()
                if (digit != null) {
                    f.setReadable((digit and 4) != 0)
                    f.setWritable((digit and 2) != 0)
                    f.setExecutable((digit and 1) != 0)
                    applied = true
                }
            }
        }

        return if (applied) {
            listOf(Line("Permissions updated: ${hidePath(f)}", LineType.INFO))
        } else {
            listOf(Line("chmod: unrecognized mode '$mode' (Android only exposes read/write/execute bits for the owner)", LineType.ERROR))
        }
    }

    private suspend fun doSiterm(args: List<String>): List<Line> {
        val fileArg = args.getOrNull(0)
        if (fileArg == null) {
            return listOf(Line("Usage: siterm <script.sh> [arguments]", LineType.ERROR))
        }
        val scriptArgs = args.drop(1)
        val f = resolveInBase(fileArg)
        if (f == null || !f.isFile) {
            return listOf(Line("siterm: script not found: $fileArg", LineType.ERROR))
        }

        val out = mutableListOf<Line>()
        val lines = try { f.readLines() } catch (e: Exception) {
            return listOf(Line("siterm: could not read script: ${e.message}", LineType.ERROR))
        }

        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            var line = raw
            scriptArgs.forEachIndexed { i, a -> line = line.replace("\$${i + 1}", a) }
            out.addAll(process(line, recordHistory = false))
            if (breakRequested) { breakRequested = false; break }
            yield()
        }
        return out
    }

    private suspend fun doRequ(args: List<String>): List<Line> {
        val method = args.getOrNull(0)?.uppercase()
        val url = args.getOrNull(1)
        val body = args.drop(2).joinToString(" ")

        if (method == null || url == null || method !in listOf("GET", "POST", "PUT", "DELETE", "PATCH")) {
            return listOf(Line("Usage: requ <GET|POST|PUT|DELETE|PATCH> <URL> [body]", LineType.ERROR))
        }

        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                if (body.isNotBlank() && method in listOf("POST", "PUT", "PATCH")) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream?.bufferedReader()?.readText() ?: ""
                val snippet = if (responseText.length > 500) responseText.take(500) + "…" else responseText
                listOf(
                    Line("HTTP $code", if (code in 200..299) LineType.INFO else LineType.ERROR),
                    Line(snippet)
                )
            } catch (e: Exception) {
                listOf(Line("requ: request failed: ${e.message}", LineType.ERROR))
            }
        }
    }

    // -----------------------------------------------------
    // stgk — ΣTerm package manager
    // -----------------------------------------------------

    private fun stgkDir(): File = File(baseDir, ".sigmaterm/stgk").apply { mkdirs() }
    private fun stgkPackagesDir(): File = File(stgkDir(), "packages").apply { mkdirs() }
    private fun stgkInstalledFile(): File = File(stgkDir(), "installed.json")

    private fun readInstalled(): JSONObject {
        val f = stgkInstalledFile()
        return if (f.isFile) {
            try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
        } else JSONObject()
    }

    private fun writeInstalled(obj: JSONObject) {
        stgkInstalledFile().writeText(obj.toString(2))
    }

    private suspend fun fetchIndex(): JSONArray? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(STGK_INDEX_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            authHeaderValue()?.let { conn.setRequestProperty("Authorization", it) }
            if (conn.responseCode !in 200..299) return@withContext null
            val text = conn.inputStream.bufferedReader().readText()
            JSONArray(text)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun doStgk(args: List<String>): List<Line> {
        val sub = args.getOrNull(0)
        val rest = args.drop(1).filter { it != "-y" }
        val out = mutableListOf<Line>()

        when (sub) {
            null, "help" -> {
                out.add(Line("stgk — ΣTerm package manager"))
                out.add(Line("  stgk install <name> [-y]"))
                out.add(Line("  stgk uninstall|remove <name> [-y]"))
                out.add(Line("  stgk show <name>"))
                out.add(Line("  stgk list-installed"))
                out.add(Line("  stgk list-upgradeable"))
                out.add(Line("  stgk update"))
                out.add(Line("  stgk upgrade [name] [-y]"))
                out.add(Line("Repository: https://github.com/EnderAstra7744/sigma-stgk-sage", LineType.DIM))
                out.add(Line("Auth: ${tokenStatusText()} (change with: sigmaterm-change-token)", LineType.DIM))
            }
            "update" -> {
                val index = fetchIndex()
                if (index == null) {
                    out.add(Line("stgk: could not reach the package index. If the repository is private, raw.githubusercontent.com requires authentication that this build does not implement yet.", LineType.ERROR))
                } else {
                    File(stgkDir(), "index_cache.json").writeText(index.toString())
                    out.add(Line("Package index updated (${index.length()} package(s) found).", LineType.INFO))
                }
            }
            "list-installed" -> {
                val installed = readInstalled()
                val keys = installed.keys().asSequence().toList()
                if (keys.isEmpty()) out.add(Line("No packages installed.", LineType.INFO))
                else keys.forEach { k -> out.add(Line("$k (${installed.getJSONObject(k).optString("version", "?")})")) }
            }
            "list-upgradeable" -> {
                val index = fetchIndex()
                val installed = readInstalled()
                if (index == null) {
                    out.add(Line("stgk: could not reach the package index.", LineType.ERROR))
                } else {
                    var found = 0
                    for (i in 0 until index.length()) {
                        val pkg = index.getJSONObject(i)
                        val name = pkg.optString("name")
                        val remoteVersion = pkg.optString("version")
                        val localVersion = installed.optJSONObject(name)?.optString("version")
                        if (localVersion != null && localVersion != remoteVersion) {
                            out.add(Line("$name: $localVersion -> $remoteVersion"))
                            found++
                        }
                    }
                    if (found == 0) out.add(Line("Everything is up to date.", LineType.INFO))
                }
            }
            "show" -> {
                val name = rest.getOrNull(0)
                if (name == null) { out.add(Line("Usage: stgk show <name>", LineType.ERROR)); return out }
                val index = fetchIndex()
                val pkg = index?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("name") == name } }
                if (pkg == null) out.add(Line("stgk: package not found: $name", LineType.ERROR))
                else {
                    out.add(Line("Name: ${pkg.optString("name")}"))
                    out.add(Line("Version: ${pkg.optString("version")}"))
                    out.add(Line("Description: ${pkg.optString("description")}"))
                    out.add(Line("URL: ${pkg.optString("url")}"))
                }
            }
            "install" -> {
                val name = rest.getOrNull(0)
                if (name == null) { out.add(Line("Usage: stgk install <name> [-y]", LineType.ERROR)); return out }
                val index = fetchIndex()
                val pkg = index?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("name") == name } }
                if (pkg == null) {
                    out.add(Line("stgk: package not found in index: $name", LineType.ERROR))
                    return out
                }
                val url = pkg.optString("url")
                val fileName = url.substringAfterLast("/").ifBlank { name }
                val dest = File(stgkPackagesDir(), fileName)
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        authHeaderValue()?.let { conn.setRequestProperty("Authorization", it) }
                        conn.connect()
                        if (conn.responseCode !in 200..299) return@withContext false
                        conn.inputStream.use { input -> FileOutputStream(dest).use { output -> input.copyTo(output) } }
                        true
                    } catch (e: Exception) { false }
                }
                if (!ok) {
                    out.add(Line("stgk: download failed for '$name'.", LineType.ERROR))
                } else {
                    val installed = readInstalled()
                    val entry = JSONObject()
                    entry.put("version", pkg.optString("version"))
                    entry.put("file", dest.name)
                    installed.put(name, entry)
                    writeInstalled(installed)
                    out.add(Line("Installed: $name (${pkg.optString("version")})", LineType.INFO))
                }
            }
            "uninstall", "remove" -> {
                val name = rest.getOrNull(0)
                if (name == null) { out.add(Line("Usage: stgk uninstall <name> [-y]", LineType.ERROR)); return out }
                val installed = readInstalled()
                val entry = installed.optJSONObject(name)
                if (entry == null) {
                    out.add(Line("stgk: package not installed: $name", LineType.ERROR))
                } else {
                    File(stgkPackagesDir(), entry.optString("file")).delete()
                    installed.remove(name)
                    writeInstalled(installed)
                    out.add(Line("Removed: $name", LineType.INFO))
                }
            }
            "upgrade" -> {
                val name = rest.getOrNull(0)
                val index = fetchIndex()
                val installed = readInstalled()
                if (index == null) { out.add(Line("stgk: could not reach the package index.", LineType.ERROR)); return out }
                val targets = if (name != null) listOf(name) else installed.keys().asSequence().toList()
                var upgraded = 0
                for (pkgName in targets) {
                    val pkg = (0 until index.length()).map { index.getJSONObject(it) }.firstOrNull { it.optString("name") == pkgName } ?: continue
                    val localVersion = installed.optJSONObject(pkgName)?.optString("version")
                    if (localVersion == pkg.optString("version")) continue
                    out.addAll(doStgk(listOf("install", pkgName, "-y")))
                    upgraded++
                }
                if (upgraded == 0) out.add(Line("Nothing to upgrade.", LineType.INFO))
            }
            "list" -> {
                val index = fetchIndex()
                if (index == null) out.add(Line("stgk: could not reach the package index.", LineType.ERROR))
                else for (i in 0 until index.length()) {
                    val pkg = index.getJSONObject(i)
                    out.add(Line("${pkg.optString("name")} (${pkg.optString("version")}) - ${pkg.optString("description")}"))
                }
            }
            else -> out.add(Line("stgk: unknown subcommand: $sub", LineType.ERROR))
        }
        return out
    }

    // -----------------------------------------------------
    // Help
    // -----------------------------------------------------

    private fun doHelp(target: String?): List<Line> {
        val descr = linkedMapOf(
            "ca" to "Change username: ca <name>",
            "cd" to "Change directory: cd <dir>",
            "ls" to "List directory contents (-l, -a)",
            "pwd" to "Show current directory",
            "mkdir" to "Create directory: mkdir <name>",
            "touch" to "Create empty file: touch <name>",
            "rm" to "Delete file/directory: rm [-r] <name>",
            "cp" to "Copy: cp <source> <dest>",
            "mv" to "Move / rename: mv <source> <dest>",
            "cat" to "Show file contents: cat <file>",
            "edit" to "Simple line editor: edit <file>",
            "get" to "Download a file: get -<source> <URL> [--cd DIR] [--forcename NAME] [--zip|--unzip]",
            "sigmaterm-storage-access" to "Request full storage access (/storage/emulated/0)",
            "sigmaterm-change-token" to "Choose a GitHub token (Manual or Automatic) for private stgk repo access",
            "battery" to "Show battery status",
            "whoami" to "Show the current username",
            "id" to "Show user identity info",
            "history" to "Show command history, !! reruns the last command",
            "alias" to "Define/list aliases: alias name=command",
            "neofetch" to "Show device/system info",
            "clear" to "Clear the screen",
            "echo" to "Print text (supports \$`VAR interpolation)",
            "exit" to "Quit the app",
            "help" to "Show this help: help <command>",
            "variables" to "Create a variable: variables NAME =: VALUE [-y]",
            "set" to "Update an existing variable: set NAME =: VALUE",
            "setname" to "Rename a variable: setname OLD =: NEW",
            "unset" to "Delete variable(s): unset [-fv] NAME... [-y]",
            "env" to "List all variables",
            "function" to "Define a function: function NAME [{p1,p2}] do: ... end",
            "return" to "Return a value from inside a function body",
            "if" to "if {exp1} {OP} {exp2} do: {function}",
            "else" to "else do: {function}  (must follow an if)",
            "for" to "for {a,b,c} do: {function}  (or a numeric range {1-5})",
            "while" to "while {condition} do: {function}",
            "break" to "Stop the nearest running for/while loop",
            "or" to "or %<0-100> {exp1,exp2} do: {func1,func2}",
            "hash" to "hash <md5|sha1|sha256|sha512> <text>",
            "chmod" to "chmod <+x|-x|+w|-w|+r|-r> <path>",
            "siterm" to "Run a .sh script line by line: siterm <file> [args]",
            "requ" to "HTTP request: requ <GET|POST|PUT|DELETE|PATCH> <URL> [body]",
            "stgk" to "ΣTerm package manager (install/uninstall/update/upgrade/...)"
        )
        val out = mutableListOf<Line>()
        if (target.isNullOrBlank()) {
            descr.toSortedMap().forEach { (k, v) -> out.add(Line(String.format("%-24s %s", k, v))) }
            if (functions.isNotEmpty()) {
                out.add(Line(""))
                out.add(Line("User-defined functions:", LineType.DIM))
                functions.keys.forEach { out.add(Line("  $it")) }
            }
        } else {
            val d = descr[target]
            if (d != null) out.add(Line("$target: $d"))
            else if (functions.containsKey(target)) out.add(Line("$target: user-defined function"))
            else out.add(Line("help: unknown command: $target", LineType.ERROR))
        }
        return out
    }

    // -----------------------------------------------------
    // neofetch
    // -----------------------------------------------------

    private fun brandBadge(letter: Char): List<String> = listOf(
        "\u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510",
        "\u2502         \u2502",
        "\u2502    $letter    \u2502",
        "\u2502         \u2502",
        "\u2502  \u03A3Term  \u2502",
        "\u2502         \u2502",
        "\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518"
    )

    /**
     * Small OS/device-brand-based theme table, in the spirit of neofetch's
     * per-distro ascii art + color scheme (see README for attribution).
     * Since this app only ever runs on Android, we key off the device
     * manufacturer instead of the OS itself so the logo/colors still vary
     * from device to device.
     */
    private fun brandTheme(): Triple<String, Int, List<String>> {
        val mfr = Build.MANUFACTURER.lowercase()
        return when {
            mfr.contains("samsung") -> Triple("Samsung", 0xFF1428A0.toInt(), brandBadge('S'))
            mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco") ->
                Triple("Xiaomi", 0xFFFF6900.toInt(), brandBadge('M'))
            mfr.contains("google") -> Triple("Pixel", 0xFF4285F4.toInt(), brandBadge('G'))
            mfr.contains("oneplus") -> Triple("OnePlus", 0xFFEB0028.toInt(), brandBadge('+'))
            mfr.contains("huawei") || mfr.contains("honor") -> Triple("Huawei", 0xFFFF0000.toInt(), brandBadge('H'))
            mfr.contains("sony") -> Triple("Sony", 0xFF1A1A1A.toInt(), brandBadge('X'))
            mfr.contains("oppo") -> Triple("OPPO", 0xFF1BA784.toInt(), brandBadge('O'))
            mfr.contains("vivo") -> Triple("Vivo", 0xFF415FFF.toInt(), brandBadge('V'))
            mfr.contains("motorola") -> Triple("Motorola", 0xFF5B54F9.toInt(), brandBadge('M'))
            else -> Triple("Android", 0xFF3DDC84.toInt(), brandBadge('A'))
        }
    }

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

        val (brandName, accent, logoLines) = brandTheme()

        val infoLines = listOf(
            "$userName@\u03A3Term" to LineType.ACCENT,
            "----------------" to LineType.DIM,
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}" to LineType.NORMAL,
            "Brand theme: $brandName" to LineType.NORMAL,
            "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" to LineType.NORMAL,
            "Uptime: ${hours}h ${mins}m" to LineType.NORMAL,
            "Memory: ${humanSize(usedMem)}/${humanSize(mi.totalMem)}" to LineType.NORMAL,
            "Storage: ${humanSize(usedBytes)}/${humanSize(totalBytes)}" to LineType.NORMAL,
            "Storage access: ${if (hasFullStorageAccess) "full" else "sandboxed"}" to LineType.NORMAL,
            "Directory: ${hidePath(curDir)}" to LineType.NORMAL
        )

        out.add(Line(""))
        val maxLines = maxOf(infoLines.size, logoLines.size)
        for (i in 0 until maxLines) {
            val (infoText, infoType) = infoLines.getOrElse(i) { "" to LineType.NORMAL }
            val logoText = logoLines.getOrElse(i) { "" }
            out.add(Line(infoText, infoType, rightText = logoText, rightColor = accent))
        }
        out.add(Line(""))

        val darkPalette = listOf(
            0xFF000000.toInt(), 0xFFCC0000.toInt(), 0xFF4E9A06.toInt(), 0xFFC4A000.toInt(),
            0xFF3465A4.toInt(), 0xFF75507B.toInt(), 0xFF06989A.toInt(), 0xFFD3D7CF.toInt()
        )
        val brightPalette = listOf(
            0xFF555753.toInt(), 0xFFEF2929.toInt(), 0xFF8AE234.toInt(), 0xFFFCE94F.toInt(),
            0xFF729FCF.toInt(), 0xFFAD7FA8.toInt(), 0xFF34E2E2.toInt(), 0xFFEEEEEC.toInt()
        )
        out.add(Line("        ", LineType.NORMAL, darkPalette))
        out.add(Line("        ", LineType.NORMAL, brightPalette))
        out.add(Line(""))

        return out
    }

    // -----------------------------------------------------
    // get (download)
    // -----------------------------------------------------

    private suspend fun doGet(args: List<String>): List<Line> {
        val out = mutableListOf<Line>()
        val sourceFlag = args.getOrNull(0)
        var url = args.getOrNull(1)

        if (sourceFlag == null || url == null) {
            out.add(Line("Usage: get -<source> <URL> [--cd <DIR>] [--forcename <NEW_NAME>] [--zip|--unzip]", LineType.ERROR))
            return out
        }

        if (sourceFlag.lowercase() == "-github" && url.contains("github.com") && url.contains("/blob/")) {
            url = url.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/")
            out.add(Line("GitHub URL converted to raw format.", LineType.INFO))
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
                else -> out.add(Line("get: unknown argument: ${args[i]}", LineType.ERROR))
            }
            i++
        }

        if (!destDir.exists()) destDir.mkdirs()

        val baseName = url.substringAfterLast("/").substringBefore("?").ifBlank { "downloaded_file" }
        val finalName = forceName ?: baseName
        val outFile = File(destDir, finalName)

        out.add(Line("Downloading [source: ${sourceFlag.removePrefix("-")}]: $url", LineType.INFO))

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
            out.add(Line("get: download failed.", LineType.ERROR))
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
                out.add(Line("Done (zip): ${hidePath(zipTarget)}", LineType.INFO))
            }
            "unzip" -> {
                val ok = withContext(Dispatchers.IO) { unzipFile(outFile, destDir) }
                outFile.delete()
                if (ok) out.add(Line("Done (extracted): ${hidePath(destDir)}", LineType.INFO))
                else out.add(Line("get: unzip failed, file left archived.", LineType.ERROR))
            }
            else -> out.add(Line("Done: ${hidePath(outFile)}", LineType.INFO))
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
