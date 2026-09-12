package com.lumora.autoinstaller

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The fcaronte/KingInstaller diagnostic, scoped to Lumora: reads how the system recorded the
 * install and applies Android Auto's golden rule -
 *
 *   "Installed by" must be exclusively com.android.vending (Play Store), and
 *   "Requested by" must be the Play Store or the Package Installer
 *   (com.google.android.packageinstaller).
 *
 * A Shizuku/ADB install records the initiating package as com.android.shell, which Android
 * Auto refuses even when the installer identity looks right - the "ADB trap".
 */
object InstallerDiagnostic {

    const val LUMORA_PACKAGE = "com.lumora"
    const val VENDING_PKG = "com.android.vending"

    data class Result(
        val installed: Boolean,
        val installing: String?,
        val initiating: String?,
        val aaCompatible: Boolean
    ) {
        val verdict: String
            get() = when {
                !installed -> "Lumora not installed"
                aaCompatible -> "OK - Android Auto should list Lumora"
                else -> "BAD - Android Auto may refuse Lumora (see rules below)"
            }
    }

    fun check(context: Context): Result {
        val pm = context.packageManager
        val installed = try {
            pm.getPackageInfo(LUMORA_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
        if (!installed) return Result(false, null, null, false)

        var installing: String? = null
        var initiating: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = pm.getInstallSourceInfo(LUMORA_PACKAGE)
            initiating = info.initiatingPackageName
            installing = info.installingPackageName
        } else {
            @Suppress("DEPRECATION")
            installing = pm.getInstallerPackageName(LUMORA_PACKAGE)
        }

        val playStoreInstalling = installing == VENDING_PKG
        val validInitiating = initiating == VENDING_PKG ||
            initiating == "com.google.android.packageinstaller" ||
            (initiating?.contains("packageinstaller") == true)
        return Result(installed, installing, initiating, playStoreInstalling && validInitiating)
    }

    /** Human label for a package name, e.g. "Google Play Store (com.android.vending)". */
    fun labelFor(context: Context, packageName: String?): String {
        if (packageName.isNullOrBlank()) return "unknown"
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            "${pm.getApplicationLabel(info)} ($packageName)"
        } catch (_: Exception) {
            packageName
        }
    }
}
