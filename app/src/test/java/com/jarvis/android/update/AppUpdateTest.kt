package com.jarvis.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test fun versionNumbersIgnoreThePrefixAndTheSuffix() {
        assertEquals(listOf(0, 4, 0), versionNumbers("v0.4.0-dev"))
        assertEquals(listOf(1, 12), versionNumbers("1.12"))
        assertTrue(versionNumbers("dernière").isEmpty())
    }

    @Test fun aHigherVersionIsNewer() {
        assertTrue(isNewer("0.4.0", "0.3.0"))
        assertTrue(isNewer("v0.10.0", "0.9.9"))
        assertTrue(isNewer("1.0", "0.9.9"))
        assertTrue(isNewer("0.4.1", "0.4.0-dev"))
    }

    @Test fun theSameOrAnOlderVersionIsNot() {
        assertFalse(isNewer("0.4.0", "0.4.0-dev"))
        assertFalse(isNewer("0.4", "0.4.0"))
        assertFalse(isNewer("0.3.9", "0.4.0"))
        assertFalse(isNewer("nightly", "0.4.0"))
    }

    private val release = """
        {"tag_name":"v0.5.0","body":" Nouveautés ","assets":[
          {"name":"jarvis-0.5.0-release.apk","browser_download_url":"https://example.test/release.apk","size":100},
          {"name":"jarvis-0.5.0-debug.apk","browser_download_url":"https://example.test/debug.apk","size":200},
          {"name":"notes.txt","browser_download_url":"https://example.test/notes.txt","size":3}]}
    """

    @Test fun aDebugBuildTakesTheDebugApk() {
        val info = parseRelease(release, debugBuild = true)!!
        assertEquals("0.5.0", info.version)
        assertEquals("jarvis-0.5.0-debug.apk", info.apkName)
        assertEquals("https://example.test/debug.apk", info.apkUrl)
        assertEquals(200L, info.apkSize)
        assertEquals("Nouveautés", info.notes)
    }

    @Test fun aReleaseBuildTakesTheOtherApk() {
        assertEquals("jarvis-0.5.0-release.apk", parseRelease(release, debugBuild = false)!!.apkName)
    }

    @Test fun anOnlyApkIsUsedWhateverItsName() {
        val json = """{"tag_name":"0.5.0","assets":[{"name":"jarvis.apk","browser_download_url":"https://example.test/j.apk"}]}"""
        val info = parseRelease(json, debugBuild = true)
        assertNotNull(info)
        assertEquals(-1L, info!!.apkSize)
    }

    @Test fun aReleaseWithoutApkOrTagOrJsonIsRefused() {
        assertNull(parseRelease("""{"tag_name":"v1","assets":[{"name":"a.zip","browser_download_url":"x"}]}""", true))
        assertNull(parseRelease("""{"assets":[]}""", true))
        assertNull(parseRelease("pas du json", true))
        assertNull(parseRelease("[]", true))
    }
}
