package com.omnisave.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

/* "Share → Omnisave" from any app: text/link lands prefilled → save → tick toast → finish. */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            try {
                val url = Regex("https?://\\S+").find(text)?.value
                if (url.isNullOrBlank()) { Toast.makeText(this@ShareReceiverActivity, "Nothing to save", Toast.LENGTH_SHORT).show() }
                else {
                    val (plat, type, isShort) = Repo.parse(url)
                    Repo.save(this@ShareReceiverActivity, AppDatabase.get(this@ShareReceiverActivity).dao(), plat, type, url, text.take(300), "")
                    Toast.makeText(this@ShareReceiverActivity, "✓ Saved", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { Toast.makeText(this@ShareReceiverActivity, "Save failed", Toast.LENGTH_SHORT).show() }
            finish()
        }
    }
}
