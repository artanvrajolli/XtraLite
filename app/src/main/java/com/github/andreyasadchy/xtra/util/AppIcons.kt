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
        AppIcon("flat", R.string.app_icon_flat, R.mipmap.ic_launcher_flat),
        AppIcon("minimal", R.string.app_icon_minimal, R.mipmap.ic_launcher_minimal),
        AppIcon("minimal_dark", R.string.app_icon_minimal_dark, R.mipmap.ic_launcher_minimal_dark),
        AppIcon("midnight", R.string.app_icon_midnight, R.mipmap.ic_launcher_midnight),
        AppIcon("amoled", R.string.app_icon_amoled, R.mipmap.ic_launcher_amoled),
        AppIcon("ocean", R.string.app_icon_ocean, R.mipmap.ic_launcher_ocean),
        AppIcon("deep_sea", R.string.app_icon_deep_sea, R.mipmap.ic_launcher_deep_sea),
        AppIcon("forest", R.string.app_icon_forest, R.mipmap.ic_launcher_forest),
        AppIcon("emerald", R.string.app_icon_emerald, R.mipmap.ic_launcher_emerald),
        AppIcon("mint", R.string.app_icon_mint, R.mipmap.ic_launcher_mint),
        AppIcon("sky", R.string.app_icon_sky, R.mipmap.ic_launcher_sky),
        AppIcon("sunset", R.string.app_icon_sunset, R.mipmap.ic_launcher_sunset),
        AppIcon("amber", R.string.app_icon_amber, R.mipmap.ic_launcher_amber),
        AppIcon("crimson", R.string.app_icon_crimson, R.mipmap.ic_launcher_crimson),
        AppIcon("rose", R.string.app_icon_rose, R.mipmap.ic_launcher_rose),
        AppIcon("grape", R.string.app_icon_grape, R.mipmap.ic_launcher_grape),
        AppIcon("neon", R.string.app_icon_neon, R.mipmap.ic_launcher_neon),
        AppIcon("mono_light", R.string.app_icon_mono_light, R.mipmap.ic_launcher_mono_light),
        AppIcon("mono_dark", R.string.app_icon_mono_dark, R.mipmap.ic_launcher_mono_dark),
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
