package com.hachicorp.nezh.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.height
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hachicorp.nezh.library.FolderKey
import com.hachicorp.nezh.library.FolderStatus
import com.hachicorp.nezh.library.LibraryFolder
import com.hachicorp.nezh.library.LibraryState
import com.hachicorp.nezh.playback.ConnectionStatus
import com.hachicorp.nezh.playback.PlaybackUiState
import com.hachicorp.nezh.ui.theme.NezhTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Aviso de licencias": last row of the folder list, with or without folders, and its screen. */
@RunWith(AndroidJUnit4::class)
class LicensesNoticeTest {

    @get:Rule
    val compose = createComposeRule()

    private val entry = "Aviso de licencias"

    private fun folder(name: String) = LibraryFolder(
        key = FolderKey("test", name), uri = Uri.parse("content://test/tree/$name"), name = name, status = FolderStatus.READY,
    )

    private fun assertEntryBelow(text: String) {
        val above = compose.onNodeWithText(text).getUnclippedBoundsInRoot()
        val notice = compose.onNodeWithText(entry).getUnclippedBoundsInRoot()
        assertTrue("$entry must be below $text", notice.top >= above.bottom)
    }

    @Test
    fun entryIsShownWithoutFoldersAndStaysLastWhenFoldersChange() {
        var library by mutableStateOf(LibraryState(loaded = true))
        compose.setContent {
            NezhTheme {
                FoldersTab(library = library, onAddFolder = {}, onReauthorize = {}, onRemove = {}, onRefresh = {}, onOpenLicenses = {})
            }
        }
        compose.onNodeWithText("No hay carpetas autorizadas.").assertIsDisplayed()
        compose.onNodeWithText(entry).assertIsDisplayed()
            .assert(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertTrue(compose.onNodeWithText(entry).getUnclippedBoundsInRoot().height.value >= 48f)

        library = LibraryState(loaded = true, folders = listOf(folder("Música"), folder("Conciertos")))
        compose.waitForIdle()
        assertEntryBelow("Música")
        assertEntryBelow("Conciertos")

        library = LibraryState(loaded = true, folders = listOf(folder("Conciertos")))
        compose.waitForIdle()
        compose.onAllNodesWithText("Música").assertCountEquals(0)
        assertEntryBelow("Conciertos")
    }

    @Test
    fun opensTheNoticeAndAFullLicenseTextAndGoesBack() {
        compose.setContent {
            NezhTheme {
                PlayerScreen(
                    playback = PlaybackUiState(connection = ConnectionStatus.CONNECTED),
                    library = LibraryState(loaded = true),
                    actions = PlayerActions(),
                )
            }
        }
        compose.onNodeWithText("Carpetas").performClick()
        compose.onNodeWithText(entry).performClick()

        compose.onNodeWithText("AndroidX Media3").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("https://github.com/androidx/media"))
        compose.onNodeWithText("https://github.com/androidx/media").assertIsDisplayed()

        // Media3's license text, complete.
        val media3Text = "Texto completo de la licencia de AndroidX Media3"
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(media3Text))
        compose.onNodeWithContentDescription(media3Text).assertIsDisplayed().performClick()
        compose.onNodeWithText("Apache License, Version 2.0").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("END OF TERMS AND CONDITIONS", substring = true)).assertExists()

        // Back: text -> list -> folders.
        Espresso.pressBack()
        compose.onNodeWithText("AndroidX Media3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText(entry).assertIsDisplayed()
    }

    @Test
    fun everyComponentOfTheInventoryIsListed() {
        compose.setContent { NezhTheme { LicensesScreen(onClose = {}) } }
        thirdPartyComponents.forEach { component ->
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(component.name))
            compose.onNodeWithText(component.name).assertIsDisplayed()
        }
    }
}
