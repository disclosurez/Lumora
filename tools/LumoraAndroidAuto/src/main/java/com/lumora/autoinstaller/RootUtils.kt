package com.lumora.autoinstaller

import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream

/**
 * Root install path, same as fcaronte/KingInstaller's: run `pm install -t -i
 * com.android.vending` through `su` when the device is rooted and Shizuku is not set up.
 */
object RootUtils {

    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/su/bin/su",
        "/data/adb/magisk/su",
        "/data/adb/ksu/bin/su"
    )

    val isRooted: Boolean
        get() {
            val tags = Build.TAGS
            if (tags != null && tags.contains("test-keys")) return true
            return SU_PATHS.any { File(it).exists() }
        }

    /**
     * Runs a single command as root. Returns the combined stdout + stderr, or null when no
     * su binary answers.
     */
    fun runSu(cmd: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes(cmd + "\n")
                os.flush()
                os.writeBytes("exit\n")
                os.flush()
            }
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
            }
            val out = readStream(process.inputStream)
            val err = readStream(process.errorStream)
            val combined = (out + err).trim()
            combined.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun readStream(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(1024)
        var n: Int
        while (input.read(data).also { n = it } != -1) {
            buffer.write(data, 0, n)
        }
        return buffer.toString("UTF-8")
    }
}
