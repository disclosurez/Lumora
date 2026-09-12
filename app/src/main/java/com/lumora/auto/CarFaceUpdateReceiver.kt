package com.lumora.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-applies the OS-version car-face choice (see applyCarAppServiceState) after an app
 * update or a reboot, without needing the app to be launched.
 *
 * The choice is made per device and the component state is persistent, but both events
 * change the ground under it: an install can land on a device whose face state is still
 * the manifest default (both services disabled -> no tile in the car), and an Android
 * version upgrade between boots must flip legacy<->projection. MY_PACKAGE_REPLACED also
 * fires after a plain reinstall, which is exactly the flow the LumoraAndroidAuto tool
 * drives - the tile must appear before the user ever opens Lumora.
 */
class CarFaceUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.applicationContext.applyCarAppServiceState()
    }
}
