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
        // license.txt of soiaf/Java-Apple-Lossless-decoder at afd5bf30 (also in third_party/java-alac-decoder).
        assertEquals("2000b58ee3d5d56a723dba1ef8e90f880f75935044681c2987a065223a69e89a", sha256(asset(LicenseText.JAVA_ALAC_DECODER.asset)))
        assertEquals(sha256(File("../third_party/java-alac-decoder/license.txt")), sha256(asset(LicenseText.JAVA_ALAC_DECODER.asset)))
        // META-INF/LICENSE.txt of checker-qual 3.43.0.
        assertEquals("e90eaa08dea089df10f2bf7f9dba63a5f6237c01562c4741e2e86773e78582d5", sha256(asset(LicenseText.MIT_CHECKER_FRAMEWORK.asset)))
    }

    /**
     * The Java ALAC decoder carries two separate notices: Peter McQuillan's BSD license and the
     * notice of David Hammerton's original decoder, complete (copyright, permission, condition,
     * disclaimer). Each is its own text, shown by its own entry.
     */
    @Test
    fun bothAlacDecoderNoticesAreShippedCompleteAndSeparate() {
        val hammerton = asset(LicenseText.HAMMERTON_ALAC_DECODER.asset)
        assertEquals("c22a56322c30e65a10ae0dcbff2ca39e62fef697bd607cf8f5a149b468ccdf6c", sha256(hammerton))
        assertEquals(sha256(File("../third_party/java-alac-decoder/LICENSE-Hammerton.txt")), sha256(hammerton))
        val text = hammerton.readText().replace(Regex("\\s+"), " ")
        listOf(
            "ALAC (Apple Lossless Audio Codec) decoder Copyright (c) 2005 David Hammerton All rights reserved.",
            "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated " +
                "documentation files (the \"Software\"), to deal in the Software without restriction, including without " +
                "limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of " +
                "the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:",
            "The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.",
            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED " +
                "TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL " +
                "THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF " +
                "CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER " +
                "DEALINGS IN THE SOFTWARE.",
        ).forEach { assertTrue("Hammerton notice lacks: $it", text.contains(it)) }

        val bsd = asset(LicenseText.JAVA_ALAC_DECODER.asset).readText()
        assertTrue(bsd.contains("Copyright (c) 2011-2014 Peter McQuillan"))
        assertTrue("the BSD license stays as published, without Hammerton's permission text", !bsd.contains("Permission is hereby granted"))

        val java = thirdPartyComponents.single { it.license == LicenseText.JAVA_ALAC_DECODER }
        val original = thirdPartyComponents.single { it.license == LicenseText.HAMMERTON_ALAC_DECODER }
        assertEquals("Peter McQuillan", java.holder)
        assertEquals("David Hammerton", original.holder)
        assertTrue(original.copyright.contains("Copyright (c) 2005 David Hammerton"))
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
