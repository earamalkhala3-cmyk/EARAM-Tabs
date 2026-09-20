package com.earam.tabs

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

class UpdateManager(private val activity: Activity) {
    companion object {
        private const val RELEASE_API = "https://api.github.com/repos/earamalkhala3-cmyk/EARAM-Tabs/releases/latest"
        private const val APK_NAME = "Earam-update.apk"
    }

    fun checkForUpdates() {
        Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                if (connection.responseCode !in 200..299) throw IllegalStateException("Update server returned ${connection.responseCode}")
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name").removePrefix("v")
                val remoteVersion = json.optInt("version_code", parseVersionCode(tag))
                val apk = json.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length()).map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk", true) }
                }
                val apkUrl = apk?.optString("browser_download_url").orEmpty()
                val sha256 = apk?.optString("sha256").orEmpty()
                activity.runOnUiThread {
                    if (remoteVersion <= BuildConfig.VERSION_CODE || apkUrl.isBlank()) {
                        Toast.makeText(activity, "Earam is up to date (${BuildConfig.VERSION_NAME})", Toast.LENGTH_LONG).show()
                    } else {
                        showUpdateDialog(tag, apkUrl, sha256)
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Update check failed: ${e.message ?: "network error"}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun parseVersionCode(version: String): Int =
        version.split(".").mapNotNull { it.toIntOrNull() }.let {
            when (it.size) {
                0 -> 0
                1 -> it[0]
                else -> it[0] * 10000 + it[1] * 100 + (it.getOrNull(2) ?: 0)
            }
        }

    private fun showUpdateDialog(version: String, url: String, sha256: String) {
        android.app.AlertDialog.Builder(activity)
            .setTitle("Earam update available")
            .setMessage("Version $version is available. Download and install it now?")
            .setNegativeButton("Later", null)
            .setPositiveButton("Update") { _, _ -> downloadAndInstall(url, sha256) }
            .show()
    }

    private fun downloadAndInstall(url: String, expectedSha256: String) {
        Toast.makeText(activity, "Downloading Earam update…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val target = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    requestMethod = "GET"
                }
                if (connection.responseCode !in 200..299) throw IllegalStateException("Download failed: ${connection.responseCode}")
                connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                if (expectedSha256.isNotBlank()) {
                    val actual = sha256(target)
                    if (!actual.equals(expectedSha256, true)) {
                        target.delete()
                        throw SecurityException("Update integrity check failed")
                    }
                }
                activity.runOnUiThread { install(target) }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Update failed: ${e.message ?: "download error"}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            android.app.AlertDialog.Builder(activity)
                .setTitle("Allow Earam to install updates")
                .setMessage("Android requires permission to install an update downloaded by Earam. Enable it once, then return to Earam.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open Settings") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    })
                }
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
