package com.sigmaterm.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ScaleGestureDetector
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sigmaterm.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: TerminalEngine
    private lateinit var audioManager: AudioManager
    private lateinit var uiPrefs: SharedPreferences

    // Command history navigation (↑ / ↓)
    private var historyIndex = -1
    private var draftBeforeHistory = ""

    // CTRL / ALT modifier state
    private var ctrlActive = false
    private var altActive = false

    private var suppressWatcher = false
    private var lastTextLength = 0

    // Pinch-to-zoom font size
    private var fontSizeSp = 13f
    private val minFontSizeSp = 9f
    private val maxFontSizeSp = 26f
    private lateinit var scaleDetector: ScaleGestureDetector

    private val manageAllFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        applyStorageAccessResult()
    }

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        applyStorageAccessResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.loadSoundEffects()

        uiPrefs = getSharedPreferences("sigmaterm_ui", MODE_PRIVATE)
        fontSizeSp = uiPrefs.getFloat("font_size_sp", 13f)
        applyFontSize()

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                fontSizeSp = (fontSizeSp * detector.scaleFactor).coerceIn(minFontSizeSp, maxFontSizeSp)
                applyFontSize()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                uiPrefs.edit().putFloat("font_size_sp", fontSizeSp).apply()
            }
        })

        engine = TerminalEngine(applicationContext)

        appendLine(Line("\u03A3Term welcome!\nType 'help' to see all commands, or 'ca <name>' to set your username.", LineType.INFO))
        updatePrompt()

        binding.commandInput.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                runCommand()
                true
            } else {
                false
            }
        }

        binding.commandInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher || s == null) return

                // Keypress click sound, only when a character was added (not on delete)
                if (s.length > lastTextLength) {
                    audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
                }
                lastTextLength = s.length

                // CTRL+C / CTRL+L shortcuts
                if (ctrlActive && s.isNotEmpty()) {
                    val last = s.last().lowercaseChar()
                    if (last == 'c') {
                        suppressWatcher = true
                        s.replace(0, s.length, "")
                        suppressWatcher = false
                        lastTextLength = 0
                        appendLine(Line("^C", LineType.DIM))
                        setCtrlActive(false)
                        historyIndex = -1
                    } else if (last == 'l') {
                        suppressWatcher = true
                        s.replace(0, s.length, "")
                        suppressWatcher = false
                        lastTextLength = 0
                        binding.outputText.text = ""
                        setCtrlActive(false)
                    }
                }
            }
        })

        setupExtraKeys()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { scaleDetector.onTouchEvent(it) }
        return super.dispatchTouchEvent(ev)
    }

    private fun applyFontSize() {
        binding.outputText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
        binding.commandInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
        binding.promptLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
    }

    override fun onResume() {
        super.onResume()
        // Silently pick up storage access if it was granted from system settings.
        val wasGranted = engine.hasFullStorageAccess
        val nowGranted = engine.refreshStorageAccess()
        if (!wasGranted && nowGranted) {
            appendLine(Line("Full storage access granted. Now browsing /storage/emulated/0", LineType.INFO))
            updatePrompt()
        }
    }

    // -----------------------------------------------------
    // sigmaterm-change-token menu
    // -----------------------------------------------------

    private fun showTokenMenu() {
        val options = arrayOf("Manual Token", "Automatic Token")
        AlertDialog.Builder(this)
            .setTitle("ΣTerm — GitHub Token")
            .setItems(options) { _, which ->
                if (which == 0) showManualTokenInput() else useAutomaticToken()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun useAutomaticToken() {
        val ok = engine.useAutomaticToken()
        if (ok) {
            appendLine(Line("Automatic token applied.", LineType.INFO))
        } else {
            appendLine(Line("No built-in token is configured in this build. Falling back to unauthenticated requests.", LineType.ERROR))
        }
    }

    private fun showManualTokenInput() {
        val input = EditText(this).apply {
            hint = "ghp_..."
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Enter GitHub Token")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val token = input.text.toString().trim()
                if (token.isBlank()) {
                    appendLine(Line("sigmaterm-change-token: empty token, nothing saved.", LineType.ERROR))
                } else {
                    engine.setManualToken(token)
                    appendLine(Line("Manual token saved.", LineType.INFO))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -----------------------------------------------------
    // Storage access permission flow
    // -----------------------------------------------------

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                applyStorageAccessResult()
            } else {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    manageAllFilesLauncher.launch(intent)
                } catch (e: Exception) {
                    manageAllFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                applyStorageAccessResult()
            } else {
                legacyPermissionLauncher.launch(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                )
            }
        }
    }

    private fun applyStorageAccessResult() {
        val granted = engine.refreshStorageAccess()
        if (granted) {
            appendLine(Line("Full storage access granted. Now browsing /storage/emulated/0", LineType.INFO))
        } else {
            appendLine(Line("Storage access was not granted. Staying in the app's private sandbox.", LineType.ERROR))
        }
        updatePrompt()
    }

    // -----------------------------------------------------
    // Extra keys bar (Termux-style)
    // -----------------------------------------------------

    private fun setupExtraKeys() {
        binding.keyEsc.setOnClickListener {
            binding.commandInput.setText("")
            historyIndex = -1
        }

        binding.keyHome.setOnClickListener {
            binding.commandInput.setSelection(0)
        }

        binding.keyEnd.setOnClickListener {
            binding.commandInput.setSelection(binding.commandInput.text.length)
        }

        binding.keyLeft.setOnClickListener {
            val pos = binding.commandInput.selectionStart
            if (pos > 0) binding.commandInput.setSelection(pos - 1)
        }

        binding.keyRight.setOnClickListener {
            val pos = binding.commandInput.selectionStart
            val len = binding.commandInput.text.length
            if (pos < len) binding.commandInput.setSelection(pos + 1)
        }

        binding.keyUp.setOnClickListener { navigateHistory(-1) }
        binding.keyDown.setOnClickListener { navigateHistory(1) }

        binding.keyPgUp.setOnClickListener {
            binding.outputScroll.scrollBy(0, -binding.outputScroll.height / 2)
        }
        binding.keyPgDn.setOnClickListener {
            binding.outputScroll.scrollBy(0, binding.outputScroll.height / 2)
        }

        binding.keyCtrl.setOnClickListener { setCtrlActive(!ctrlActive) }
        binding.keyAlt.setOnClickListener { setAltActive(!altActive) }
    }

    private fun setCtrlActive(active: Boolean) {
        ctrlActive = active
        binding.keyCtrl.setBackgroundColor(
            if (active) ContextCompat.getColor(this, R.color.key_active_bg) else Color.parseColor("#1A1A1A")
        )
        binding.keyCtrl.setTextColor(
            if (active) ContextCompat.getColor(this, R.color.key_active_fg) else ContextCompat.getColor(this, R.color.key_fg)
        )
    }

    private fun setAltActive(active: Boolean) {
        altActive = active
        binding.keyAlt.setBackgroundColor(
            if (active) ContextCompat.getColor(this, R.color.key_active_bg) else Color.parseColor("#1A1A1A")
        )
        binding.keyAlt.setTextColor(
            if (active) ContextCompat.getColor(this, R.color.key_active_fg) else ContextCompat.getColor(this, R.color.key_fg)
        )
    }

    private fun navigateHistory(direction: Int) {
        val hist = engine.history
        if (hist.isEmpty()) return

        if (historyIndex == -1 && direction < 0) {
            draftBeforeHistory = binding.commandInput.text.toString()
            historyIndex = hist.size
        }

        historyIndex += direction

        when {
            historyIndex < 0 -> historyIndex = 0
            historyIndex >= hist.size -> {
                historyIndex = -1
                binding.commandInput.setText(draftBeforeHistory)
                binding.commandInput.setSelection(binding.commandInput.text.length)
                return
            }
        }

        binding.commandInput.setText(hist[historyIndex])
        binding.commandInput.setSelection(binding.commandInput.text.length)
    }

    // -----------------------------------------------------
    // Command execution
    // -----------------------------------------------------

    private fun runCommand() {
        val text = binding.commandInput.text.toString()
        binding.commandInput.setText("")
        historyIndex = -1
        if (text.isBlank()) return

        appendLine(Line("${engine.promptLabel()}$text", LineType.ECHO))

        lifecycleScope.launch {
            val result = engine.process(text)
            for (line in result) {
                when (line.text) {
                    "__CLEAR__" -> binding.outputText.text = ""
                    "__EXIT__" -> finish()
                    "__REQUEST_STORAGE__" -> requestStorageAccess()
                    "__SHOW_TOKEN_MENU__" -> showTokenMenu()
                    else -> appendLine(line)
                }
            }
            updatePrompt()
            scrollToBottom()
        }
    }

    private fun updatePrompt() {
        binding.promptLabel.text = engine.promptLabel()
    }

    private fun appendLine(line: Line) {
        if (line.paletteColors != null) {
            val span = SpannableStringBuilder()
            for (color in line.paletteColors) {
                val start = span.length
                span.append("   ")
                span.setSpan(BackgroundColorSpan(color), start, span.length, 0)
            }
            span.append("\n")
            binding.outputText.append(span)
            scrollToBottom()
            return
        }

        val color = when (line.type) {
            LineType.ERROR -> ContextCompat.getColor(this, R.color.terminal_error)
            LineType.INFO -> ContextCompat.getColor(this, R.color.terminal_info)
            LineType.ACCENT -> ContextCompat.getColor(this, R.color.terminal_prompt)
            LineType.DIM -> Color.parseColor("#777777")
            LineType.ECHO -> ContextCompat.getColor(this, R.color.terminal_prompt)
            LineType.NORMAL -> ContextCompat.getColor(this, R.color.terminal_fg)
        }
        val prefix = when (line.type) {
            LineType.ERROR -> "[X] "
            LineType.INFO -> "[*] "
            else -> ""
        }
        val leftText = prefix + line.text

        val span = SpannableStringBuilder()
        if (line.rightText != null) {
            // Side-by-side rendering (used by neofetch): left info column + right logo column.
            val padded = leftText.padEnd(30)
            val startLeft = span.length
            span.append(padded)
            span.setSpan(ForegroundColorSpan(color), startLeft, span.length, 0)

            val startRight = span.length
            span.append(line.rightText)
            span.setSpan(ForegroundColorSpan(line.rightColor ?: color), startRight, span.length, 0)
            span.append("\n")
        } else {
            span.append(leftText)
            span.setSpan(ForegroundColorSpan(color), 0, leftText.length, 0)
            span.append("\n")
        }

        binding.outputText.append(span)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.outputScroll.post {
            binding.outputScroll.fullScroll(View.FOCUS_DOWN)
        }
    }
}
