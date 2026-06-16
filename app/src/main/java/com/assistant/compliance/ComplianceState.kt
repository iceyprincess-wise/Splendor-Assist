package com.assistant.compliance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

object ComplianceState {

    fun battery(context: Context): Boolean {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return true

        val pm =
            context.getSystemService(
                Context.POWER_SERVICE
            ) as PowerManager

        return pm.isIgnoringBatteryOptimizations(
            context.packageName
        )
    }

    fun overlay(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun notifications(context: Context): Boolean {

        if (Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) return true

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun accessibility(context: Context): Boolean {

        val enabled =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

        return enabled.contains(
            context.packageName,
            true
        )
    }

    fun ready(context: Context): Boolean {

        return battery(context)
            && overlay(context)
            && notifications(context)
            && accessibility(context)
    }

    fun summary(context: Context): String {

        if (ready(context))
            return "ENGINE READY"

        val failed =
            mutableListOf<String>()

        if (!battery(context))
            failed += "BATTERY"

        if (!overlay(context))
            failed += "OVERLAY"

        if (!notifications(context))
            failed += "NOTIFICATIONS"

        if (!accessibility(context))
            failed += "ACCESSIBILITY"

        return "BLOCKED: " +
            failed.joinToString(", ")
    }
}
