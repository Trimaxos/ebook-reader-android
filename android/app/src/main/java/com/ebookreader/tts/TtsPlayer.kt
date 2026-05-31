package com.ebookreader.tts

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class TtsPlayer(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private var cacheDir: File? = null
    private var currentAudioFile: File? = null
    var onPlaybackComplete: (() -> Unit)? = null

    init {
        cacheDir = File(context.cacheDir, "tts_audio")
        cacheDir?.mkdirs()
    }

    fun play(audioBytes: ByteArray) {
        // Save bytes to temp file and play
        stop()

        val audioFile = File(cacheDir, "tts_${System.nanoTime()}.mp3")
        audioFile.writeBytes(audioBytes)
        currentAudioFile = audioFile

        exoPlayer = ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(audioFile))
            setMediaItem(mediaItem)
            prepare()
            play()

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        onPlaybackComplete?.invoke()
                    }
                }
            })
        }
    }

    fun playFromUri(uri: Uri) {
        stop()
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            prepare()
            play()

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        onPlaybackComplete?.invoke()
                    }
                }
            })
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun stop() {
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
    }

    fun isPlaying(): Boolean = exoPlayer?.isPlaying ?: false

    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0

    fun getDuration(): Long = exoPlayer?.duration ?: 0

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
    }

    fun cleanup() {
        stop()
        cacheDir?.deleteRecursively()
    }
}
