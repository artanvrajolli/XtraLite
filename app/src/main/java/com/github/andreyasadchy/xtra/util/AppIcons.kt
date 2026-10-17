package com.github.andreyasadchy.xtra.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp

/**
 * Selectable launcher icons. Each icon has an activity-alias in AndroidManifest.xml named
 * ".LauncherIcon<Id in CamelCase>"; exactly one alias is enabled at a time.
 */
object AppIcons {

    class AppIcon(val id: String, val label: Int, val icon: Int) {
        val aliasName = XtraApp::class.java.name.substringBeforeLast(".") + ".LauncherIcon" + id.split('_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    const val DEFAULT = "lite"

    val icons = listOf(
        AppIcon("lite", R.string.app_icon_lite, R.mipmap.ic_launcher),
        AppIcon("classic", R.string.app_icon_classic, R.mipmap.ic_launcher_classic),
        AppIcon("purple", R.string.app_icon_purple, R.mipmap.ic_launcher_purple),
        AppIcon("amoled", R.string.app_icon_amoled, R.mipmap.ic_launcher_amoled),
        AppIcon("minimal", R.string.app_icon_minimal, R.mipmap.ic_launcher_minimal),
    )

    fun getCurrent(context: Context): AppIcon {
        val packageManager = context.packageManager
        return icons.find {
            when (packageManager.getComponentEnabledSetting(ComponentName(context.packageName, it.aliasName))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> it.id == DEFAULT
                else -> false
            }
        } ?: icons.first()
    }

    /**
     * Re-enables the default icon if no launcher entry is enabled, e.g. after an update removed
     * the icon the user had picked.
     */
    fun ensureLauncherEntry(context: Context) {
        val packageManager = context.packageManager
        val anyEnabled = icons.any {
            when (packageManager.getComponentEnabledSetting(ComponentName(context.packageName, it.aliasName))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> it.id == DEFAULT
                else -> false
            }
        }
        if (!anyEnabled) {
            setCurrent(context, icons.first { it.id == DEFAULT })
        }
    }

    fun setCurrent(context: Context, icon: AppIcon) {
        val packageManager = context.packageManager
        // enable the new entry before disabling the old one so the app never has no launcher entry
        packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, icon.aliasName),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        icons.filter { it != icon }.forEach {
            packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, it.aliasName),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
