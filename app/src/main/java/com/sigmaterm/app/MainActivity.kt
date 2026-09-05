package com.sigmaterm.app

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sigmaterm.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: TerminalEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = TerminalEngine(applicationContext)

        appendLine(Line("\u03A3Term'e hoş geldiniz. Kullanıcı adı değiştirmek için: ca <isim>", LineType.INFO))
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
    }

    private fun runCommand() {
        val text = binding.commandInput.text.toString()
        binding.commandInput.setText("")
        if (text.isBlank()) return

        appendLine(Line("${engine.promptLabel()}$text", LineType.ECHO))

        lifecycleScope.launch {
            val result = engine.process(text)
            for (line in result) {
                when (line.text) {
                    "__CLEAR__" -> binding.outputText.text = ""
                    "__EXIT__" -> finish()
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
        val text = prefix + line.text
        val span = SpannableStringBuilder(text + "\n")
        span.setSpan(ForegroundColorSpan(color), 0, text.length, 0)
        binding.outputText.append(span)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.outputScroll.post {
            binding.outputScroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
