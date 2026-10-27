package com.github.andreyasadchy.xtra.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdateTest {
    @Test fun comparesLiteVersionsNumerically() {
        assertTrue(ReleaseUpdate.isNewer("v2.60.9-lite.10", "2.60.9-lite.9"))
        assertTrue(ReleaseUpdate.isNewer("v2.61.0-lite.1", "2.60.9-lite.10"))
        assertTrue(ReleaseUpdate.isNewer("v3.0.0-lite.1", "2.60.9-lite.2"))
        assertFalse(ReleaseUpdate.isNewer("v2.60.9-lite.2", "2.60.9-lite.2"))
        assertFalse(ReleaseUpdate.isNewer("v2.60.8-lite.10", "2.60.9-lite.2"))
    }

    @Test fun rejectsUnrelatedOrInvalidReleaseTags() {
        for (version in listOf("latest", "v2.60.10", "v2.60.10-lite.1-beta", "v999999999999.0.0-lite.1")) {
            assertFalse(ReleaseUpdate.isNewer(version, "2.60.9-lite.2"))
        }
    }

    private fun release(tag: String = "v2.60.9-lite.3", prerelease: Boolean = false, draft: Boolean = false,
                        url: String = "https://github.com/artanvrajolli/XtraLite/releases/download/v2.60.9-lite.3/Xtra-Lite.apk") =
        Json.parseToJsonElement("""{
          "tag_name":"$tag", "draft":$draft, "prerelease":$prerelease,
          "assets":[
            {"name":"source.zip", "browser_download_url":"https://example.com/source.zip"},
            {"name":"app-debug.apk", "browser_download_url":"https://example.com/debug.apk"},
            {"name":"Xtra-Lite.apk", "size":123456, "browser_download_url":"$url"}
          ]
        }""").jsonObject

    @Test fun selectsTheReleaseApkRegardlessOfContentType() {
        val update = ReleaseUpdate.fromRelease(release(), "2.60.9-lite.2")!!
        assertEquals("2.60.9-lite.3", update.version)
        assertEquals(123456L, update.size)
        assertTrue(update.url.endsWith("/Xtra-Lite.apk"))
    }

    @Test fun doesNotOfferCurrentOlderDraftOrPrereleaseBuilds() {
        assertNull(ReleaseUpdate.fromRelease(release(tag = "v2.60.9-lite.2"), "2.60.9-lite.2"))
        assertNull(ReleaseUpdate.fromRelease(release(tag = "v2.60.8-lite.2"), "2.60.9-lite.2"))
        assertNull(ReleaseUpdate.fromRelease(release(prerelease = true), "2.60.9-lite.2"))
        assertNull(ReleaseUpdate.fromRelease(release(draft = true), "2.60.9-lite.2"))
    }

    @Test fun requiresAnApkWithASecureDownloadUrl() {
        assertNull(ReleaseUpdate.fromRelease(release(url = "http://example.com/app.apk"), "2.60.9-lite.2"))
        assertNull(ReleaseUpdate.fromRelease(Json.parseToJsonElement("""{"tag_name":"v2.60.9-lite.3","assets":[]}""").jsonObject, "2.60.9-lite.2"))
    }
}
