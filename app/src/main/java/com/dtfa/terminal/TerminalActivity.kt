package com.dtfa.terminal

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TerminalActivity : AppCompatActivity() {

    private lateinit var outputText: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var inputLine: EditText
    private var session: TerminalSession? = null
    private val terminal = AnsiLiteTerminal()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        outputText = findViewById(R.id.outputText)
        outputScroll = findViewById(R.id.outputScroll)
        inputLine = findViewById(R.id.inputLine)

        findViewById<Button>(R.id.btnCtrlC).setOnClickListener { session?.sendInterrupt() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            terminal.clear()
            outputText.text = ""
        }

        inputLine.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || isEnter) {
                sendCurrentInput()
                true
            } else false
        }

        startSession()
    }

    private fun startSession() {
        val installer = RootfsInstaller(this)
        session = TerminalSession(
            context = this,
            rootfsDir = installer.rootfsDir,
            onOutput = { bytes, len ->
                val text = terminal.feed(bytes, len)
                runOnUiThread {
                    outputText.text = text
                    outputScroll.post { outputScroll.fullScroll(android.view.View.FOCUS_DOWN) }
                }
            },
            onFinished = { exitCode ->
                runOnUiThread {
                    outputText.append(formatExitMessage(exitCode))
                }
            }
        )
        session?.start(rows = 30, cols = 90)
    }

    private fun formatExitMessage(exitCode: Int): String {
        if (exitCode <= -1000) {
            val signal = -exitCode - 1000
            val name = when (signal) {
                4 -> "SIGILL (kod uyumsuzluğu/bozuk binary olabilir)"
                6 -> "SIGABRT"
                7 -> "SIGBUS (mimari/uyumsuzluk olabilir)"
                8 -> "SIGFPE"
                9 -> "SIGKILL"
                11 -> "SIGSEGV (proot binary'si bu cihazla uyumsuz olabilir)"
                31 -> "SIGSYS (bir syscall engellenmiş olabilir — SELinux/seccomp)"
                else -> "sinyal $signal"
            }
            return getString(R.string.session_ended_signal_fmt, name)
        }
        return getString(R.string.session_ended_fmt, exitCode)
    }

    private fun sendCurrentInput() {
        val text = inputLine.text.toString()
        session?.write(text + "\n")
        inputLine.setText("")
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.destroy()
    }
}
