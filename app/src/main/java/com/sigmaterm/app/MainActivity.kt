package com.sigmaterm.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sigmaterm.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var shell: ShellEngine

    private val errorColor = Color.parseColor("#FF5252")
    private val infoColor = Color.parseColor("#FFD740")
    private val promptColor = Color.parseColor("#B0B0B0")
    private val pathColor = Color.parseColor("#4FC3F7")
    private val normalColor = Color.parseColor("#E0E0E0")

    private val requestStorage = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        shell = ShellEngine(this)

        requestStoragePermission()

        appendWelcome()
        updatePrompt()

        binding.etInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                runCommand()
                true
            } else {
                false
            }
        }

        binding.btnSend.setOnClickListener { runCommand() }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            val perms = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val need = perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (need.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, need.toTypedArray(), requestStorage)
            }
        }
    }

    private fun appendWelcome() {
        appendColored("[*] ΣTerm'e hoş geldiniz. Giriş yapmak için: ca <isim>\n", infoColor)
    }

    private fun updatePrompt() {
        binding.tvPrompt.text = shell.getPrompt()
    }

    private fun runCommand() {
        val input = binding.etInput.text.toString()
        binding.etInput.setText("")

        // Echo the command line
        val prompt = shell.getPrompt()
        appendColored(prompt, promptColor)
        appendColored(input + "\n", normalColor)

        if (input.trim().isEmpty()) {
            updatePrompt()
            return
        }

        val results = shell.execute(input)

        for (out in results) {
            when {
                out.text == "\u000C" -> {
                    binding.tvOutput.text = ""
                    appendWelcome()
                }
                out.isError -> appendColored(out.text + "\n", errorColor)
                out.isInfo -> appendColored(out.text + "\n", infoColor)
                else -> appendColored(out.text + "\n", normalColor)
            }
        }

        if (input.trim() == "exit") {
            finish()
            return
        }

        updatePrompt()
        scrollToBottom()
    }

    private fun appendColored(text: String, color: Int) {
        val sb = SpannableStringBuilder(binding.tvOutput.text)
        val start = sb.length
        sb.append(text)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.tvOutput.text = sb
    }

    private fun scrollToBottom() {
        binding.scrollOutput.post {
            binding.scrollOutput.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestStorage) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Depolama izni olmadan dosya işlemleri kısıtlı çalışır", Toast.LENGTH_LONG).show()
            }
        }
    }
}
