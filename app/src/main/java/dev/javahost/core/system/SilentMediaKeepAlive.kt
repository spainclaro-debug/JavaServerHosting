package dev.javahost.core.system

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import dev.javahost.BuildConfig
import dev.javahost.R
import dev.javahost.core.storage.SettingsStore

/**
 * Best-effort "silent media" keep-alive.
 *
 * Some OEM power managers are more reluctant to freeze a process that owns an
 * active audio session, so looping a near-silent track can - *marginally* -
 * improve background survival on those builds. This is a hack of last resort,
 * not a reliability guarantee:
 *
 *  - it is compiled in only when [BuildConfig.ENABLE_SILENT_MEDIA_HACK] is true
 *    (false in this build) *and* the user explicitly enabled
 *    [dev.javahost.core.model.AppSettings.silentMediaHack];
 *  - it deliberately does **not** request audio focus, so it can never pause or
 *    duck music/podcasts/calls;
 *  - the track is played at volume 0.01, and is released on every stop path.
 *
 * Only the foreground service is expected to touch this class.
 */
class SilentMediaKeepAlive(
    context: Context,
    private val settings: SettingsStore
) {

    private val appContext: Context = context.applicationContext
    private var player: MediaPlayer? = null

    /** Feature flag AND user setting; both must be on. */
    fun isEnabled(): Boolean =
        BuildConfig.ENABLE_SILENT_MEDIA_HACK && settings.current().silentMediaHack

    /** True while the silent track is actually looping. */
    @get:Synchronized
    val isRunning: Boolean
        get() = player?.isPlaying == true

    /**
     * Starts the silent loop when enabled. Idempotent and never throws: if the
     * audio stack refuses to play, the server simply runs without the hack.
     */
    @Synchronized
    fun start(): Boolean {
        if (!isEnabled()) return false
        if (player != null) return true
        return try {
            val mediaPlayer = MediaPlayer()
            // No AudioAttributes/AudioFocusRequest on purpose: we must not take
            // audio focus away from the user's music or calls.
            val descriptor = appContext.resources.openRawResourceFd(R.raw.silence)
            try {
                mediaPlayer.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length
                )
            } finally {
                descriptor.close()
            }
            mediaPlayer.isLooping = true
            mediaPlayer.setVolume(VOLUME, VOLUME)
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Silent keep-alive MediaPlayer error what=$what extra=$extra")
                releasePlayer()
                true
            }
            mediaPlayer.prepare()
            mediaPlayer.start()
            player = mediaPlayer
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Silent media keep-alive unavailable on this device", t)
            releasePlayer()
            false
        }
    }

    /** Stops and releases the silent loop. Safe to call repeatedly. */
    @Synchronized
    fun stop() {
        releasePlayer()
    }

    private fun releasePlayer() {
        val current = player ?: return
        player = null
        runCatching { if (current.isPlaying) current.stop() }
            .onFailure { Log.w(TAG, "Could not stop silent media player", it) }
        runCatching { current.reset() }
            .onFailure { Log.w(TAG, "Could not reset silent media player", it) }
        runCatching { current.release() }
            .onFailure { Log.w(TAG, "Could not release silent media player", it) }
    }

    companion object {
        private const val TAG = "SilentMediaKeepAlive"

        /** Near-inaudible but non-zero, which is what some OEM heuristics look for. */
        private const val VOLUME = 0.01f
    }
}
