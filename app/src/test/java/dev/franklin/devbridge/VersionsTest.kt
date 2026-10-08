package dev.franklin.devbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

    private fun newer(a: String, b: String) =
        assertTrue("$a should be newer than $b", dev.franklin.devbridge.update.Versions.compare(a, b) > 0)

    private fun same(a: String, b: String) =
        assertEquals("$a should equal $b", 0, dev.franklin.devbridge.update.Versions.compare(a, b))

    @Test
    fun comparesComponentsNumerically() {
        newer("1.1", "1.0")
        newer("2.0", "1.9.9")
        newer("1.0.1", "1.0")
        newer("1.0.0.1", "1.0.0")
    }

    @Test
    fun doesNotFallBackToStringOrdering() {
        // The case that catches a naive implementation: "1.10" < "1.9" as text.
        newer("1.10", "1.9")
        newer("1.0.10", "1.0.9")
        newer("10.0", "9.99")
    }

    @Test
    fun ignoresATagsLeadingV() {
        same("v1.2.0", "1.2.0")
        same("V1.2.0", "1.2.0")
        newer("v1.3.0", "1.2.9")
    }

    @Test
    fun treatsMissingComponentsAsZero() {
        same("1.0", "1.0.0")
        same("1", "1.0.0")
        newer("1.0.1", "1")
    }

    @Test
    fun ranksPreReleasesBelowTheFinalVersion() {
        newer("1.0", "1.0-beta")
        newer("1.0", "1.0-rc1")
        newer("2.0-beta", "1.9")
        same("1.0-beta", "1.0-beta")
    }

    @Test
    fun survivesMalformedVersions() {
        // Whatever the server sends, this must not throw.
        same("", "")
        same("nonsense", "nonsense")
        newer("1.0", "")
        newer("1.0", "nonsense")
        dev.franklin.devbridge.update.Versions.compare("...", "1..2")
    }

    @Test
    fun anIdenticalVersionIsNotAnUpdate() {
        same("1.0", "1.0")
        assertTrue(dev.franklin.devbridge.update.Versions.compare("1.0", "1.1") < 0)
    }
}
