package org.moire.ultrasonic.subsonic

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.moire.ultrasonic.R
import org.moire.ultrasonic.data.ActiveServerProvider.Companion.isOffline
import org.moire.ultrasonic.domain.MusicDirectory
import org.moire.ultrasonic.service.MediaPlayerController
import org.moire.ultrasonic.service.MusicServiceFactory.getMusicService
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.EntryByDiscAndTrackComparator
import org.moire.ultrasonic.util.ModalBackgroundTask
import org.moire.ultrasonic.util.Util
import java.util.*

class DownloadHandler(
    val mediaPlayerController: MediaPlayerController,
    val networkAndStorageChecker: NetworkAndStorageChecker
) {
    private val MAX_SONGS = 500

    fun download(fragment: Fragment, append: Boolean, save: Boolean, autoPlay: Boolean, playNext: Boolean, shuffle: Boolean, songs: List<MusicDirectory.Entry?>) {
        val onValid = Runnable {
            if (!append && !playNext) {
                mediaPlayerController.clear()
            }
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            mediaPlayerController.download(songs, save, autoPlay, playNext, shuffle, false)
            val playlistName: String? = fragment.arguments?.getString(Constants.INTENT_EXTRA_NAME_PLAYLIST_NAME)
            if (playlistName != null) {
                mediaPlayerController.suggestedPlaylistName = playlistName
            }
            if (autoPlay) {
                if (Util.getShouldTransitionOnPlaybackPreference(fragment.activity)) {
                    fragment.findNavController().popBackStack(R.id.playerFragment, true)
                    fragment.findNavController().navigate(R.id.playerFragment)
                }
            } else if (save) {
                Util.toast(fragment.context, fragment.resources.getQuantityString(R.plurals.select_album_n_songs_pinned, songs.size, songs.size))
            } else if (playNext) {
                Util.toast(fragment.context, fragment.resources.getQuantityString(R.plurals.select_album_n_songs_play_next, songs.size, songs.size))
            } else if (append) {
                Util.toast(fragment.context, fragment.resources.getQuantityString(R.plurals.select_album_n_songs_added, songs.size, songs.size))
            }
        }
        onValid.run()
    }

    fun downloadPlaylist(fragment: Fragment, id: String, name: String?, save: Boolean, append: Boolean, autoplay: Boolean, shuffle: Boolean, background: Boolean, playNext: Boolean, unpin: Boolean) {
        downloadRecursively(fragment, id, name, false, false, save, append, autoplay, shuffle, background, playNext, unpin, false)
    }

    fun downloadShare(fragment: Fragment, id: String, name: String?, save: Boolean, append: Boolean, autoplay: Boolean, shuffle: Boolean, background: Boolean, playNext: Boolean, unpin: Boolean) {
        downloadRecursively(fragment, id, name, true, false, save, append, autoplay, shuffle, background, playNext, unpin, false)
    }

    fun downloadRecursively(
        fragment: Fragment,
        id: String?,
        save: Boolean,
        append: Boolean,
        autoPlay: Boolean,
        shuffle: Boolean,
        background: Boolean,
        playNext: Boolean,
        unpin: Boolean,
        isArtist: Boolean
    ) {
        if (id.isNullOrEmpty()) return
        downloadRecursively(
            fragment,
            id,
            "",
            isShare = false,
            isDirectory = true,
            save = save,
            append = append,
            autoPlay = autoPlay,
            shuffle = shuffle,
            background = background,
            playNext = playNext,
            unpin = unpin,
            isArtist = isArtist)
    }

    fun downloadRecursively(
        fragment: Fragment,
        id: String,
        name: String?,
        isShare: Boolean,
        isDirectory: Boolean,
        save: Boolean,
        append: Boolean,
        autoPlay: Boolean,
        shuffle: Boolean,
        background: Boolean,
        playNext: Boolean,
        unpin: Boolean,
        isArtist: Boolean
    ) {
        val activity = fragment.activity as Activity
        val task = object: ModalBackgroundTask<List<MusicDirectory.Entry>>(
            activity,
            false
        ) {

            @Throws(Throwable::class)
            override fun doInBackground(): List<MusicDirectory.Entry> {
                val musicService = getMusicService(activity)
                val songs: MutableList<MusicDirectory.Entry> = LinkedList()
                val root: MusicDirectory
                if (!isOffline(activity) && isArtist && Util.getShouldUseId3Tags(activity)) {
                    getSongsForArtist(id, songs)
                } else {
                    if (isDirectory) {
                        root = if (!isOffline(activity) && Util.getShouldUseId3Tags(activity))
                            musicService.getAlbum(id, name, false, activity, null)
                        else
                            musicService.getMusicDirectory(id, name, false, activity, null)
                    } else if (isShare) {
                        root = MusicDirectory()
                        val shares = musicService.getShares(true, activity, null)
                        for (share in shares) {
                            if (share.id == id) {
                                for (entry in share.getEntries()) {
                                    root.addChild(entry)
                                }
                                break
                            }
                        }
                    } else {
                        root = musicService.getPlaylist(id, name, activity, null)
                    }
                    getSongsRecursively(root, songs)
                }
                return songs
            }

            @Throws(Exception::class)
            private fun getSongsRecursively(parent: MusicDirectory, songs: MutableList<MusicDirectory.Entry>) {
                if (songs.size > MAX_SONGS) {
                    return
                }
                for (song in parent.getChildren(false, true)) {
                    if (!song.isVideo) {
                        songs.add(song)
                    }
                }
                val musicService = getMusicService(activity)
                for ((id1, _, _, title) in parent.getChildren(true, false)) {
                    var root: MusicDirectory
                    root = if (!isOffline(activity) && Util.getShouldUseId3Tags(activity)) musicService.getAlbum(id1, title, false, activity, null)
                    else musicService.getMusicDirectory(id1, title, false, activity, null)
                    getSongsRecursively(root, songs)
                }
            }

            @Throws(Exception::class)
            private fun getSongsForArtist(id: String, songs: MutableCollection<MusicDirectory.Entry>) {
                if (songs.size > MAX_SONGS) {
                    return
                }
                val musicService = getMusicService(activity)
                val artist = musicService.getArtist(id, "", false, activity, null)
                for ((id1) in artist.getChildren()) {
                    val albumDirectory = musicService.getAlbum(id1, "", false, activity, null)
                    for (song in albumDirectory.getChildren()) {
                        if (!song.isVideo) {
                            songs.add(song)
                        }
                    }
                }
            }

            override fun done(songs: List<MusicDirectory.Entry>) {
                if (Util.getShouldSortByDisc(activity)) {
                    Collections.sort(songs, EntryByDiscAndTrackComparator())
                }
                if (songs.isNotEmpty()) {
                    if (!append && !playNext && !unpin && !background) {
                        mediaPlayerController.clear()
                    }
                    networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
                    if (!background) {
                        if (unpin) {
                            mediaPlayerController.unpin(songs)
                        } else {
                            mediaPlayerController.download(songs, save, autoPlay, playNext, shuffle, false)
                            if (!append && Util.getShouldTransitionOnPlaybackPreference(activity)) {
                                fragment.findNavController().popBackStack(R.id.playerFragment, true)
                                fragment.findNavController().navigate(R.id.playerFragment)
                            }
                        }
                    } else {
                        if (unpin) {
                            mediaPlayerController.unpin(songs)
                        } else {
                            mediaPlayerController.downloadBackground(songs, save)
                        }
                    }
                }
            }
        }
        task.execute()
    }
}
