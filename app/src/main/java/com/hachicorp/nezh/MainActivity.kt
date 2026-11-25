package com.hachicorp.nezh

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hachicorp.nezh.library.LibraryNotice
import com.hachicorp.nezh.library.LyricsReader
import com.hachicorp.nezh.library.MusicLibrary
import com.hachicorp.nezh.playback.ConnectionStatus
import com.hachicorp.nezh.playback.PlayerConnection
import com.hachicorp.nezh.playback.technicalInfo
import com.hachicorp.nezh.ui.NowPlayingSupport
import com.hachicorp.nezh.ui.PlayerActions
import com.hachicorp.nezh.ui.PlayerScreen
import com.hachicorp.nezh.ui.theme.NezhTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var playerConnection: PlayerConnection
    private val library: MusicLibrary get() = (application as NezhApplication).library

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerConnection = PlayerConnection(this)
        library.load()
        keepQueueWithinLibrary()
        enableEdgeToEdge()
        setContent {
            NezhTheme {
                val playback by playerConnection.state.collectAsState()
                val libraryState by library.state.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                // The picker result is delivered even if the Activity was recreated meanwhile.
                val pickFolder = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri -> uri?.let(library::addFolder) }

                val noticeText = libraryState.notice?.let { noticeText(it) }
                LaunchedEffect(libraryState.notice) {
                    if (noticeText != null) {
                        snackbarHostState.showSnackbar(noticeText)
                        library.dismissNotice()
                    }
                }

                val actions = PlayerActions(
                    onPlayPause = playerConnection::playPause,
                    onNext = playerConnection::next,
                    onPrevious = playerConnection::previous,
                    onToggleShuffle = playerConnection::toggleShuffle,
                    onCycleRepeat = playerConnection::cycleRepeatMode,
                    onQueueItemClick = playerConnection::playAt,
                    onTrackClick = { index ->
                        val tracks = libraryState.tracks
                        lifecycleScope.launch { playerConnection.playTracks(tracks, index) }
                    },
                    onAddToQueue = { track ->
                        if (playerConnection.addToQueue(track)) {
                            val title = track.title.ifBlank { getString(R.string.untitled_track) }
                            scope.launch {
                                // One confirmation at a time: adding several tracks must not queue snackbars.
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(
                                    getString(R.string.added_to_queue, title),
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        }
                    },
                    onRemoveFromQueue = { queue, index -> playerConnection.removeFromQueue(queue, index) },
                    onMoveInQueue = { queue, from, to -> playerConnection.moveInQueue(queue, from, to) },
                    onClearUpcoming = { queue -> playerConnection.clearUpcoming(queue) },
                    onAddFolder = { launchFolderPicker { pickFolder.launch(null) } },
                    onReauthorizeFolder = { folder -> launchFolderPicker { pickFolder.launch(folder.uri) } },
                    onRemoveFolder = { folder -> library.removeFolder(folder.key) },
                    onRefreshLibrary = library::refresh,
                    onRetryConnection = playerConnection::connect,
                )
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    PlayerScreen(
                        playback = playback,
                        library = libraryState,
                        actions = actions,
                        modifier = Modifier.padding(innerPadding),
                        nowPlayingSupport = nowPlayingSupport,
                    )
                }
            }
        }
    }

    private val lyricsReader by lazy { LyricsReader(this) }

    /** Position, seeking, lyrics and technical info for Now Playing. File reads run on IO. */
    private val nowPlayingSupport by lazy {
        NowPlayingSupport(
            positionMs = playerConnection::positionMs,
            durationMs = playerConnection::durationMs,
            seekTo = playerConnection::seekTo,
            currentDocument = playerConnection::currentDocumentUri,
            loadLyrics = { uri -> withContext(Dispatchers.IO) { lyricsReader.read(uri) } },
            technicalInfo = {
                // Player state is read on the main thread; the file size query runs on IO.
                val format = playerConnection.currentAudioFormat()
                val mediaId = playerConnection.state.value.currentMediaId
                val track = mediaId?.let { library.state.value.tracksById[it] }
                val duration = track?.durationMs ?: playerConnection.durationMs().takeIf { it > 0 }
                val uri = playerConnection.currentDocumentUri()
                val size = uri?.let { withContext(Dispatchers.IO) { documentSize(it) } }
                technicalInfo(format, track?.relativePath ?: track?.title, size, duration)
            },
        )
    }

    /** Size of a document from its provider, or null if the provider does not report it. */
    private fun documentSize(uri: Uri): Long? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (e: SecurityException) {
        Log.i(TAG, "Size not readable: ${e.javaClass.simpleName}")
        null
    } catch (e: IllegalArgumentException) {
        Log.i(TAG, "Size not readable: ${e.javaClass.simpleName}")
        null
    }

    override fun onStart() {
        super.onStart()
        playerConnection.connect()
    }

    override fun onStop() {
        playerConnection.disconnect()
        super.onStop()
    }

    /**
     * Keeps the queue consistent with the library: removes tracks whose folder was removed or lost
     * permission, and tracks a rescan excluded (e.g. `.nomedia`). Runs whenever the library
     * changes or the UI (re)connects.
     */
    private fun keepQueueWithinLibrary() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Never prune before the folder list is known.
                val filters = library.state
                    .map { if (it.loaded) it.queueFilter() else null }
                    .distinctUntilChanged()
                val connected = playerConnection.state
                    .map { it.connection == ConnectionStatus.CONNECTED }
                    .distinctUntilChanged()
                combine(filters, connected) { filter, isConnected -> filter?.takeIf { isConnected } }
                    // distinct before filtering, so a reconnect (null -> filter) prunes again.
                    .distinctUntilChanged()
                    .filterNotNull()
                    .collect { playerConnection.removeItemsNotAllowed(it) }
            }
        }
    }

    private fun launchFolderPicker(launch: () -> Unit) {
        try {
            launch()
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No folder picker available", e)
            Toast.makeText(this, R.string.error_picker_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}

@Composable
private fun noticeText(notice: LibraryNotice): String = when (notice) {
    LibraryNotice.FolderAlreadyAdded -> stringResource(R.string.notice_folder_already_added)
    LibraryNotice.StorageRootNotAllowed -> stringResource(R.string.notice_storage_root_not_allowed)
    LibraryNotice.InvalidFolder -> stringResource(R.string.notice_invalid_folder)
    LibraryNotice.PermissionNotPersisted -> stringResource(R.string.notice_permission_not_persisted)
    is LibraryNotice.PermissionsLost ->
        pluralStringResource(R.plurals.notice_permissions_lost, notice.count, notice.count)
    LibraryNotice.SaveFailed -> stringResource(R.string.notice_save_failed)
}
