package com.hachicorp.nezh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The notice screen only shows what is shipped: every text it opens exists, and the official texts are verbatim. */
class ThirdPartyNoticesTest {

    private fun asset(path: String) = File("src/main/assets", path)

    private fun sha256(file: File) =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun everyLicenseTextIsShipped() {
        LicenseText.entries.forEach { assertTrue("${it.asset} missing or empty", asset(it.asset).length() > 0) }
    }

    @Test
    fun officialLicenseTextsAreVerbatim() {
        // LICENSE of the androidx/media 1.11.1 tag archive (Apache License 2.0).
        assertEquals("cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", sha256(asset(LicenseText.APACHE_2_0.asset)))
        // META-INF/LICENSE.txt of checker-qual 3.43.0.
        assertEquals("e90eaa08dea089df10f2bf7f9dba63a5f6237c01562c4741e2e86773e78582d5", sha256(asset(LicenseText.MIT_CHECKER_FRAMEWORK.asset)))
    }

    @Test
    fun libraryListsAreShippedAndWellFormed() {
        val lists = thirdPartyComponents.mapNotNull { it.librariesAsset }
        assertTrue(lists.isNotEmpty())
        lists.forEach { path ->
            val lines = asset(path).readLines().filter { it.isNotBlank() }
            assertTrue("$path is empty", lines.isNotEmpty())
            lines.forEach { assertTrue("$path: '$it'", Regex("[\\w.-]+:[\\w.-]+:[\\w.-]+").matches(it)) }
        }
    }

    @Test
    fun componentsHaveUniqueNamesAndALicense() {
        // The names key the list items.
        assertEquals(thirdPartyComponents.size, thirdPartyComponents.map { it.name }.toSet().size)
        thirdPartyComponents.forEach { assertTrue(it.name, it.licenseName.isNotBlank()) }
    }
}
