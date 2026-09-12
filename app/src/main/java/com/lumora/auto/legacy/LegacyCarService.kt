package com.lumora.auto.legacy

import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

/**
 * Legacy Android Auto projection service (com.google.android.apps.auto.sdk).
 *
 * Registered exactly the way fcaronte/AABrowser is - MAIN action plus
 * CATEGORY_PROJECTION/CATEGORY_PROJECTION_OEM - because that is the only car face the
 * modern AA host lists for a sideloaded app on phones below Android 15. The template
 * service (LumoraCarAppService) is what got Lumora denied on those hosts:
 * CAR.VALIDATOR rejects a non-Play POI/NAVIGATION template app with
 * "failed all other checks", while a legacy projection service of the same install
 * passes. See the manifest entry and CarAppRegistration for how the two faces are
 * switched per OS version.
 */
class LegacyCarService : CarActivityService() {

    override fun getCarActivity(): Class<out CarActivity> = LegacyCarActivity::class.java
}
