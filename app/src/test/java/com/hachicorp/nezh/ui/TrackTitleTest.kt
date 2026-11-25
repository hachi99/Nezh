package com.hachicorp.nezh.ui

import com.hachicorp.nezh.library.resolveTitle
import org.junit.Assert.assertEquals
import org.junit.Test

/** The text shown for a track: its title, or the "untitled" text when it has none. */
class TrackTitleTest {

    private val untitled = "Sin título"

    @Test
    fun aTitleIsShownAsIs() {
        assertEquals("Blue Line", titleOrUntitled("Blue Line", untitled))
        assertEquals("ブルーライン", titleOrUntitled("ブルーライン", untitled))
        assertEquals("Cafe\u0301 del Mar", titleOrUntitled("Cafe\u0301 del Mar", untitled))
    }

    @Test
    fun noTitleShowsTheUntitledText() {
        assertEquals(untitled, titleOrUntitled(null, untitled))
        assertEquals(untitled, titleOrUntitled("", untitled))
        assertEquals(untitled, titleOrUntitled("  ", untitled))
    }

    @Test
    fun presentationFollowsTheResolvedTrackTitle() {
        // Library and playback resolve the title with the same rule; the UI only adds "untitled".
        assertEquals("Song", titleOrUntitled(resolveTitle(tagTitle = null, fileTitle = "Song"), untitled))
        assertEquals("Tag", titleOrUntitled(resolveTitle(tagTitle = "Tag", fileTitle = "Song"), untitled))
        assertEquals(untitled, titleOrUntitled(resolveTitle(tagTitle = " ", fileTitle = null), untitled))
    }
}
