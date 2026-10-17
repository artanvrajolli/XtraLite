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
        AppIcon("mic", R.string.app_icon_mic, R.mipmap.ic_launcher_mic),
        AppIcon("joystick", R.string.app_icon_joystick, R.mipmap.ic_launcher_joystick),
        AppIcon("popcorn", R.string.app_icon_popcorn, R.mipmap.ic_launcher_popcorn),
        AppIcon("trophy", R.string.app_icon_trophy, R.mipmap.ic_launcher_trophy),
        AppIcon("camera", R.string.app_icon_camera, R.mipmap.ic_launcher_camera),
        AppIcon("satellite", R.string.app_icon_satellite, R.mipmap.ic_launcher_satellite),
        AppIcon("music", R.string.app_icon_music, R.mipmap.ic_launcher_music),
        AppIcon("storm", R.string.app_icon_storm, R.mipmap.ic_launcher_storm),
        AppIcon("mountain", R.string.app_icon_mountain, R.mipmap.ic_launcher_mountain),
        AppIcon("fox", R.string.app_icon_fox, R.mipmap.ic_launcher_fox),
        AppIcon("owl", R.string.app_icon_owl, R.mipmap.ic_launcher_owl),
        AppIcon("pizza", R.string.app_icon_pizza, R.mipmap.ic_launcher_pizza),
        AppIcon("coffee", R.string.app_icon_coffee, R.mipmap.ic_launcher_coffee),
        AppIcon("robot", R.string.app_icon_robot, R.mipmap.ic_launcher_robot),
        AppIcon("alien", R.string.app_icon_alien, R.mipmap.ic_launcher_alien),
        AppIcon("skull", R.string.app_icon_skull, R.mipmap.ic_launcher_skull),
        AppIcon("shield", R.string.app_icon_shield, R.mipmap.ic_launcher_shield),
        AppIcon("target", R.string.app_icon_target, R.mipmap.ic_launcher_target),
        AppIcon("infinity", R.string.app_icon_infinity, R.mipmap.ic_launcher_infinity),
        AppIcon("ticket", R.string.app_icon_ticket, R.mipmap.ic_launcher_ticket),
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
