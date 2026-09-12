package com.lumora.auto

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.lumora.auto.legacy.LegacyCarService

/**
 * Enforces the single car face the host is allowed to find.
 *
 * The registration mirrors fcaronte/AABrowser - a statically enabled legacy projection
 * service (auto/legacy/) - because that is the shape the modern host lists for a
 * sideloaded install on Android 14 and on Android 15+ alike. The CarAppLibrary template
 * service is no longer declared at all; declaring its `template` descriptor entry made
 * the host classify Lumora as a template app and, with no enabled template service,
 * list nothing instead of falling back to the projection face.
 *
 * The manifest already declares [LegacyCarService] enabled, so a fresh install needs
 * nothing. This function still runs on launch (and from CarFaceUpdateReceiver after
 * install/reboot) to repair installations that predate the static registration - a
 * device that once had the legacy service disabled keeps that persisted state until
 * someone writes it back.
 */
internal fun Context.applyCarAppServiceState() {
    val legacyComponent = ComponentName(this, LegacyCarService::class.java)

    try {
        if (packageManager.getComponentEnabledSetting(legacyComponent) !=
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        ) {
            // DONT_KILL_APP: without it the platform is entitled to kill the process that
            // just changed its own component, which here is the app mid-launch.
            packageManager.setComponentEnabledSetting(
                legacyComponent, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i("CarAppRegistration", "Car face: legacy projection service enabled")
        }
    } catch (t: Throwable) {
        // A device that refuses the change (or has no such component after a partial install)
        // keeps whatever state it had; the car face is a fallback, never a launch requirement.
        Log.w("CarAppRegistration", "Could not set car app service state", t)
    }
}
