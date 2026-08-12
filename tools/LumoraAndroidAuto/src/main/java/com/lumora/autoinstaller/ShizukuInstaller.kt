package com.lumora.autoinstaller

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Installs the Lumora APK with the Android Auto "trust trick": the installer
 * identity is spoofed to `com.android.vending` so Android Auto lists the app,
 * following the AAAD approach. Requires Shizuku (root or wireless adb).
 */
object ShizukuInstaller {

    private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"
    private const val INSTALLER_ID = "com.android.vending"
    private const val TMP_APK = "/data/local/tmp/lumora.apk"

    /** True when Shizuku is connected and this app already holds its permission. */
    fun isUsable(): Boolean =
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /**
     * Runs the AAAD-style install via Shizuku's shell. Returns the combined
     * output of the `pm install` command (used to detect "Success"), or null
     * if everything ran but produced no output.
     *
     * @param log callback receiving each command's output as it runs.
     */
    suspend fun installWithSpoof(apkFile: File, log: (String) -> Unit): String? =
        withContext(Dispatchers.IO) {
            var installOutput: String? = null

            // 1. Enable AA dev-mode unknown sources in Android Auto's own prefs
            //    file (tolerate the file not existing).
            run(
                """F=/data/data/$GEARHEAD_PKG/shared_prefs/${GEARHEAD_PKG}_preferences.xml; if [ -f "${'$'}F" ] && ! grep -q 'allow_unknown_sources' "${'$'}F"; then sed -i 's#</map>#<boolean name="allow_unknown_sources" value="true" />\n</map>#' "${'$'}F"; fi""",
                log
            )

            // 2. Enable developer settings broadcast.
            run("am broadcast -a com.google.android.gms.car.ACTION_ENABLE_DEVELOPER_SETTINGS $GEARHEAD_PKG --ez enabled true", log)

            // 3. Allow unknown sources broadcast.
            run("am broadcast -a com.google.android.gms.car.ACTION_SET_ALLOW_UNKNOWN_SOURCES $GEARHEAD_PKG --ez allow true", log)

            // 4. Copy the APK to /data/local/tmp, chmod it, then pm install with
            //    the installer identity spoofed to com.android.vending.
            val src = shellEscape(apkFile.absolutePath)
            val installCmd = "cp $src $TMP_APK && chmod 644 $TMP_APK && pm install -r -i $INSTALLER_ID --originating-uri \"https://play.google.com/store/apps\" --include-stopped-packages $TMP_APK"
            installOutput = run(installCmd, log)

            // 5. Tell Android Auto to refresh its package list.
            run("am broadcast -a com.google.android.gms.car.ACTION_REFRESH_PACKAGES $GEARHEAD_PKG", log)

            installOutput
        }

    /**
     * Runs a single shell command through Shizuku's process API, capturing
     * stdout + stderr, and logs it.
     */
    private fun run(cmd: String, log: (String) -> Unit): String? {
        log("> $cmd")
        try {
            val binder = Shizuku.getBinder()
                ?: run {
                    log("Shizuku binder not connected")
                    return null
                }
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(arrayOf("/system/bin/sh", "-c", cmd), null, null)

            // Close stdin so the shell does not wait for input.
            process.outputStream?.let { it.close() }

            val stdout = process.inputStream?.let { fd ->
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
            } ?: ByteArray(0)
            val stderr = process.errorStream?.let { fd ->
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
            } ?: ByteArray(0)

            process.waitFor()

            val out = stdout.toString(Charsets.UTF_8).trim()
            val err = stderr.toString(Charsets.UTF_8).trim()
            val combined = when {
                out.isBlank() -> err
                err.isBlank() -> out
                else -> "$out\n$err"
            }
            if (combined.isNotBlank()) log(combined)
            return combined.ifBlank { null }
        } catch (e: Exception) {
            log("Shell command failed: ${e.message}")
            return null
        }
    }

    /** Single-quote escaping for shell arguments. */
    private fun shellEscape(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
