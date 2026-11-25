package com.hachicorp.nezh.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hachicorp.nezh.R

/**
 * The text shown for a track: its title, or [untitled] when it has none (blank). The title itself
 * (tag, else file name) is resolved by the library and playback with resolveTitle; this only
 * decides what to show when even that is missing. Used for visible text and TalkBack labels alike.
 */
internal fun titleOrUntitled(title: String?, untitled: String): String =
    title?.takeIf { it.isNotBlank() } ?: untitled

/** [titleOrUntitled] with Nezh's "untitled" text. */
@Composable
internal fun titleOrUntitled(title: String?): String =
    titleOrUntitled(title, stringResource(R.string.untitled_track))
