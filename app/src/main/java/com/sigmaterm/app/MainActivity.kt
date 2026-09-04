package com.sigmaterm.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var tvPrompt: TextView
    private lateinit var scrollOutput: ScrollView

    private val shell = SigmaShell()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled on next command */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        tvOutput = findViewById(R.id.tvOutput)
        etInput = findViewById(R.id.etInput)
        tvPrompt = findViewById(R.id.tvPrompt)
        scrollOutput = findViewById(R.id.scrollOutput)

        requestStoragePermissions()
        shell.init(this)

        appendInfo(getString(R.string.welcome_message))
        updatePrompt()

        etInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                executeCommand()
                true
            } else false
        }

        etInput.requestFocus()
    }

    private fun requestStoragePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun executeCommand() {
        val line = etInput.text.toString().trim()
        etInput.setText("")

        if (line.isEmpty()) return

        // Echo the command with prompt
        appendRaw("${tvPrompt.text}$line\n")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                shell.execute(line)
            }
            when (result) {
                is ShellResult.Output -> appendRaw(result.text)
                is ShellResult.Error -> appendError(result.message)
                is ShellResult.Info -> appendInfo(result.message)
                is ShellResult.Clear -> tvOutput.text = ""
                is ShellResult.Exit -> {
                    appendInfo("Çıkılıyor...")
                    finish()
                }
            }
            updatePrompt()
            scrollToBottom()
        }
    }

    private fun updatePrompt() {
        val user = shell.userName
        val path = shell.displayPath()
        val prompt = "$user@ΣTerm:$pathΣ $ "
        tvPrompt.text = prompt
    }

    private fun appendRaw(text: String) {
        tvOutput.append(text)
        if (!text.endsWith("\n")) tvOutput.append("\n")
    }

    private fun appendError(msg: String) {
        val ssb = SpannableStringBuilder("[X] $msg\n")
        ssb.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.error_red)),
            0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvOutput.append(ssb)
    }

    private fun appendInfo(msg: String) {
        val ssb = SpannableStringBuilder("[*] $msg\n")
        ssb.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.info_yellow)),
            0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvOutput.append(ssb)
    }

    private fun scrollToBottom() {
        scrollOutput.post {
            scrollOutput.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
