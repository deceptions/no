package com.lagradost.shiro.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity
import com.lagradost.shiro.BuildConfig
import com.lagradost.shiro.R
import com.lagradost.shiro.utils.ErrorLogger
import com.lagradost.shiro.utils.mvvm.logError
import java.io.File
import java.net.URLEncoder

/**
 * Lets users view, copy, save, share or wipe the locally captured error/crash log
 * (see [ErrorLogger]), and file it as a GitHub issue against the Nekomo repo.
 */
class ErrorLogActivity : CyaneaAppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error_log)

        supportActionBar?.show()
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.error_log_title)

        refreshLogText()

        findViewById<android.widget.Button>(R.id.error_log_copy_btt).setOnClickListener {
            copyToClipboard()
        }
        findViewById<android.widget.Button>(R.id.error_log_share_btt).setOnClickListener {
            shareLog()
        }
        findViewById<android.widget.Button>(R.id.error_log_save_btt).setOnClickListener {
            saveLogToDownloads()
        }
        findViewById<android.widget.Button>(R.id.error_log_github_btt).setOnClickListener {
            reportOnGithub()
        }
        findViewById<android.widget.Button>(R.id.error_log_clear_btt).setOnClickListener {
            confirmClear()
        }
    }

    private fun refreshLogText() {
        findViewById<TextView>(R.id.error_log_text).text = ErrorLogger.getLogText()
    }

    private fun fullLogWithDeviceInfo(): String {
        return ErrorLogger.deviceInfoHeader() + "\n" + ErrorLogger.getLogText()
    }

    private fun copyToClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Nekomo error log", fullLogWithDeviceInfo()))
            Toast.makeText(this, R.string.error_log_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun shareLog() {
        try {
            val file = ErrorLogger.getLogFile()
            val intent = Intent(Intent.ACTION_SEND)
            if (file != null) {
                val uri: Uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", file)
                intent.putExtra(Intent.EXTRA_STREAM, uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.type = "text/plain"
            } else {
                intent.type = "text/plain"
                intent.putExtra(Intent.EXTRA_TEXT, fullLogWithDeviceInfo())
            }
            startActivity(Intent.createChooser(intent, getString(R.string.error_log_share)))
        } catch (e: Exception) {
            logError(e)
            Toast.makeText(this, R.string.error_log_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveLogToDownloads() {
        try {
            val fileName = "nekomo_error_log_${System.currentTimeMillis()}.txt"
            val content = fullLogWithDeviceInfo()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    Toast.makeText(this, R.string.error_log_saved, Toast.LENGTH_SHORT).show()
                    return
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                File(downloadsDir, fileName).writeText(content)
                Toast.makeText(this, R.string.error_log_saved, Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, R.string.error_log_save_failed, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logError(e)
            Toast.makeText(this, R.string.error_log_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun reportOnGithub() {
        try {
            // GitHub issue URLs support pre-filling the title/body via query params; keep the
            // body short since very long URLs can get rejected by browsers/GitHub.
            val body = buildString {
                append("**Describe what happened:**\n\n\n")
                append("**Error log (most recent first, truncated):**\n```\n")
                append(ErrorLogger.deviceInfoHeader())
                append(ErrorLogger.getLogText().takeLast(3000))
                append("\n```\n")
            }
            val encodedBody = URLEncoder.encode(body, "UTF-8").replace("+", "%20")
            val url = "https://github.com/Nekomo-App/Nekomo/issues/new?title=" +
                    URLEncoder.encode("Crash report", "UTF-8") +
                    "&body=$encodedBody"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            logError(e)
            Toast.makeText(this, R.string.error_log_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(R.string.error_log_clear_confirm_title)
            .setMessage(R.string.error_log_clear_confirm_message)
            .setPositiveButton(R.string.error_log_clear) { _, _ ->
                ErrorLogger.clear()
                refreshLogText()
                Toast.makeText(this, R.string.error_log_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
