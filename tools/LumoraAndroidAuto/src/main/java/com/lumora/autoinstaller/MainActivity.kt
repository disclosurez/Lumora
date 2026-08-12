package com.lumora.autoinstaller

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : AppCompatActivity() {

    private companion object {
        const val PERMISSION_REQUEST_CODE = 100
        const val LUMORA_PACKAGE = "com.lumora"
    }

    private lateinit var statusView: TextView
    private lateinit var versionLine: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnInstall: Button
    private lateinit var btnOpen: Button

    private var running = false

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    log("Shizuku permission granted - retrying install")
                    running = false
                    startInstall()
                } else {
                    log("Shizuku permission denied - will use system installer")
                    running = false
                    btnInstall.isEnabled = true
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        versionLine = findViewById(R.id.versionLine)
        progressBar = findViewById(R.id.progress)
        btnInstall = findViewById(R.id.btnInstall)
        btnOpen = findViewById(R.id.btnOpen)

        btnInstall.setOnClickListener { startInstall() }
        btnOpen.setOnClickListener { openLumora() }
    }

    override fun onStart() {
        super.onStart()
        if (Shizuku.pingBinder()) {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }
    }

    override fun onStop() {
        super.onStop()
        if (Shizuku.pingBinder()) {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
    }

    override fun onResume() {
        super.onResume()
        updateShizukuState()
    }

    private fun updateShizukuState() {
        versionLine.text = when {
            ShizukuInstaller.isUsable() -> "Shizuku: ready"
            else -> "Shizuku: not available - will use system installer"
        }
    }

    private fun startInstall() {
        if (running) return
        running = true
        btnInstall.isEnabled = false
        btnOpen.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0

        lifecycleScope.launch {
            try {
                log("Fetching latest release...")
                val (tag, url) = GitHubFetcher.latestReleaseInfo()
                log("Found Lumora $tag")

                val apk = File(getExternalFilesDir(null), "Lumora-$tag.apk")
                log("Downloading $url")
                GitHubFetcher.download(url, apk) { bytes, total ->
                    runOnUiThread {
                        if (total > 0) progressBar.max = total.toInt()
                        progressBar.progress = bytes.toInt()
                    }
                }
                log("Download complete: ${apk.absolutePath}")

                if (ShizukuInstaller.isUsable()) {
                    log("Installing with Shizuku (spoofed installer com.android.vending)...")
                    val output = ShizukuInstaller.installWithSpoof(apk) { line -> log(line) }
                    handleInstallResult(output?.contains("Success") == true, output)
                } else if (Shizuku.pingBinder()) {
                    log("Shizuku permission needed - granted via Shizuku app")
                    running = false
                    Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                } else {
                    fallbackInstall(apk)
                }
            } catch (e: Exception) {
                log("Error: ${e.message}")
                running = false
                btnInstall.isEnabled = true
            }
        }
    }

    private fun handleInstallResult(success: Boolean, output: String?) {
        if (success) {
            progressBar.visibility = View.INVISIBLE
            log("Install succeeded")
            onInstalled()
        } else {
            progressBar.visibility = View.INVISIBLE
            log("Install may have failed: ${output ?: "no output from pm install"}")
            running = false
            btnInstall.isEnabled = true
        }
    }

    private fun fallbackInstall(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            log("Launching system installer...")
            startActivity(intent)
            // System installer runs asynchronously; we cannot verify the outcome here.
            running = false
            btnInstall.isEnabled = true
        } catch (e: ActivityNotFoundException) {
            log("No system installer found: ${e.message}")
            running = false
            btnInstall.isEnabled = true
        } catch (e: Exception) {
            log("Could not launch system installer: ${e.message}")
            running = false
            btnInstall.isEnabled = true
        }
    }

    private fun onInstalled() {
        val installer = packageManager.getInstallerPackageName(LUMORA_PACKAGE)
        log("Installer: $installer")
        running = false
        btnInstall.isEnabled = true
        btnOpen.isEnabled = true
        btnOpen.visibility = View.VISIBLE
    }

    private fun openLumora() {
        val intent = packageManager.getLaunchIntentForPackage(LUMORA_PACKAGE)
        if (intent != null) {
            try {
                startActivity(intent)
            } catch (e: Exception) {
                log("Could not open Lumora: ${e.message}")
            }
        } else {
            log("Lumora not installed yet")
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            statusView.append(msg + "\n")
        }
    }
}
