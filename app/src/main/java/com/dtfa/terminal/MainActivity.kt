package com.dtfa.terminal

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)

        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            Toast.makeText(this, R.string.error_no_arm64, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val installer = RootfsInstaller(this)
        if (installer.isInstalled()) {
            goToTerminal()
            return
        }

        progress.isIndeterminate = false
        progress.max = 100
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    installer.install { count, path ->
                        runOnUiThread {
                            progressText.text = getString(R.string.setup_progress_fmt, count, path)
                        }
                    }
                }
                goToTerminal()
            } catch (t: Throwable) {
                progressText.text = "Kurulum hatası: ${t.message}"
            }
        }
    }

    private fun goToTerminal() {
        startActivity(Intent(this, TerminalActivity::class.java))
        finish()
    }
}
