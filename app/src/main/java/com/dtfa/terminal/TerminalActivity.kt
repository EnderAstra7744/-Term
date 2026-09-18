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
                    outputText.append(getString(R.string.session_ended_fmt, exitCode))
                }
            }
        )
        session?.start(rows = 30, cols = 90)
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
