package com.lumora.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/** Downloads only make sense on a device with local storage the user actually browses - not a TV box. */
fun isTvDevice(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
