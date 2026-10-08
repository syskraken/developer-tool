package dev.franklin.devbridge

import dev.franklin.devbridge.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateParsingTest {

    private val release = """
        {"tag_name":"v1.1.0","html_url":"https://github.com/syskraken/developer-tool/releases/tag/v1.1.0",
         "assets":[
           {"name":"devbridge-1.1.0.apk.sha256","browser_download_url":"https://x/devbridge-1.1.0.apk.sha256"},
           {"name":"devbridge-1.1.0.apk","browser_download_url":"https://x/devbridge-1.1.0.apk"}
         ]}
    """.trimIndent()

    @Test
    fun findsTheApkAndItsChecksumRegardlessOfAssetOrder() {
        val result = UpdateChecker.parse(release, "1.0.0")
        assertTrue(result is UpdateChecker.Result.Available)
        val r = (result as UpdateChecker.Result.Available).release
        assertEquals("1.1.0", r.version)
        assertEquals("https://x/devbridge-1.1.0.apk", r.apkUrl)
        assertEquals("https://x/devbridge-1.1.0.apk.sha256", r.sha256Url)
    }

    @Test
    fun sameOrOlderReleaseIsNotAnUpdate() {
        assertTrue(UpdateChecker.parse(release, "1.1.0") is UpdateChecker.Result.UpToDate)
        assertTrue(UpdateChecker.parse(release, "2.0") is UpdateChecker.Result.UpToDate)
    }

    @Test
    fun releaseWithoutApkStillReportsAnUpdateWithNoApkUrl() {
        val r = (UpdateChecker.parse("""{"tag_name":"v2.0.0","html_url":"u","assets":[]}""", "1.0") as UpdateChecker.Result.Available).release
        assertNull(r.apkUrl)
    }

    @Test
    fun releaseWithoutTagFails() {
        assertTrue(UpdateChecker.parse("""{"assets":[]}""", "1.0") is UpdateChecker.Result.Failed)
    }
}
