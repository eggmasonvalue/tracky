package dev.eggmasonvalue.tracky.core.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** Required at runtime for direct LAN connections on our minimum Android version (API 37). */
object LocalNetworkPermission {
    const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK
    const val DENIED_MESSAGE =
        "Local network access is denied. Grant Nearby devices in Tracky's app permissions to reach the Linux appliance."

    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
}
