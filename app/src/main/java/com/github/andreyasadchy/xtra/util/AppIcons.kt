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
        AppIcon("retro_tv", R.string.app_icon_retro_tv, R.mipmap.ic_launcher_retro_tv),
        AppIcon("bolt", R.string.app_icon_bolt, R.mipmap.ic_launcher_bolt),
        AppIcon("star", R.string.app_icon_star, R.mipmap.ic_launcher_star),
        AppIcon("leaf", R.string.app_icon_leaf, R.mipmap.ic_launcher_leaf),
        AppIcon("rocket", R.string.app_icon_rocket, R.mipmap.ic_launcher_rocket),
        AppIcon("invader", R.string.app_icon_invader, R.mipmap.ic_launcher_invader),
        AppIcon("headphones", R.string.app_icon_headphones, R.mipmap.ic_launcher_headphones),
        AppIcon("broadcast", R.string.app_icon_broadcast, R.mipmap.ic_launcher_broadcast),
        AppIcon("diamond", R.string.app_icon_diamond, R.mipmap.ic_launcher_diamond),
        AppIcon("moon", R.string.app_icon_moon, R.mipmap.ic_launcher_moon),
        AppIcon("sun", R.string.app_icon_sun, R.mipmap.ic_launcher_sun),
        AppIcon("wave", R.string.app_icon_wave, R.mipmap.ic_launcher_wave),
        AppIcon("flame", R.string.app_icon_flame, R.mipmap.ic_launcher_flame),
        AppIcon("crown", R.string.app_icon_crown, R.mipmap.ic_launcher_crown),
        AppIcon("ghost", R.string.app_icon_ghost, R.mipmap.ic_launcher_ghost),
        AppIcon("cat", R.string.app_icon_cat, R.mipmap.ic_launcher_cat),
        AppIcon("heart", R.string.app_icon_heart, R.mipmap.ic_launcher_heart),
        AppIcon("clapper", R.string.app_icon_clapper, R.mipmap.ic_launcher_clapper),
        AppIcon("gamepad", R.string.app_icon_gamepad, R.mipmap.ic_launcher_gamepad),
        AppIcon("planet", R.string.app_icon_planet, R.mipmap.ic_launcher_planet),
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
