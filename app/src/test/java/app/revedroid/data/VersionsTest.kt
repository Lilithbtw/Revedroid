package app.revedroid.data

import app.revedroid.data.update.Versions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun newerPatchMinorAndMajorAreUpdates() {
        assertTrue(Versions.isNewer("0.1.3", "0.1.2"))
        assertTrue(Versions.isNewer("0.2.0", "0.1.9"))
        assertTrue(Versions.isNewer("1.0.0", "0.9.99"))
    }

    @Test
    fun sameOrOlderVersionsAreNot() {
        assertFalse(Versions.isNewer("0.1.2", "0.1.2"))
        assertFalse(Versions.isNewer("0.1.1", "0.1.2"))
        assertFalse(Versions.isNewer("0.9.0", "1.0.0"))
    }

    @Test
    fun numbersCompareNumericallyNotAsText() {
        assertTrue(Versions.isNewer("0.1.10", "0.1.9"))
    }

    @Test
    fun tagPrefixAndBuildSuffixAreIgnored() {
        assertTrue(Versions.isNewer("v0.1.3", "0.1.2-debug"))
        assertFalse(Versions.isNewer("v0.1.2", "0.1.2-debug"))
    }

    @Test
    fun missingPartsCountAsZero() {
        assertFalse(Versions.isNewer("1.0", "1.0.0"))
        assertTrue(Versions.isNewer("1.0.1", "1.0"))
    }
}
