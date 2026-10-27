package com.github.andreyasadchy.xtra.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ReleaseUpdate(val version: String, val url: String, val size: Long?) {
    companion object {
        const val RELEASE_URL = "https://api.github.com/repos/artanvrajolli/XtraLite/releases/latest"

        // Compare the Lite release sequence numerically: lite.10 follows lite.9.
        private fun versionParts(version: String): List<Int>? {
            val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)-lite\\.(\\d+)$")
                .matchEntire(version.removeSuffix("-DEBUG")) ?: return null
            return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
        }

        fun isNewer(candidate: String, installed: String): Boolean {
            val next = versionParts(candidate) ?: return false
            val current = versionParts(installed) ?: return false
            return next.zip(current).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
        }

        fun fromRelease(release: JsonObject, installed: String): ReleaseUpdate? {
            if (release["draft"]?.jsonPrimitive?.booleanOrNull == true ||
                release["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
            val version = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            if (!isNewer(version, installed)) return null
            val asset = release["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull {
                val name = it["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                name.endsWith(".apk", ignoreCase = true) && !name.contains("debug", ignoreCase = true)
            } ?: return null
            val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.startsWith("https://") } ?: return null
            return ReleaseUpdate(version.removePrefix("v"), url, asset["size"]?.jsonPrimitive?.longOrNull)
        }
    }
}
