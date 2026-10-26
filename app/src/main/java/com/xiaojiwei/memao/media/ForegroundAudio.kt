package com.xiaojiwei.memao.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class AudioState(
    val path: String? = null, val playing: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val error: String? = null,
)

/** One player per app ViewModel. Local private files only; never a background service. */
class ForegroundAudio(context: Context) : DefaultLifecycleObserver {
    private val app=context.applicationContext
    private val player=ExoPlayer.Builder(app)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
        .setHandleAudioBecomingNoisy(true)
        .build()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable=MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = mutable
    private var foreground=false
    private val lockReceiver=object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if(intent?.action==Intent.ACTION_SCREEN_OFF) pause() }
    }
    init {
        ContextCompat.registerReceiver(app,lockReceiver,IntentFilter(Intent.ACTION_SCREEN_OFF),ContextCompat.RECEIVER_NOT_EXPORTED)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { update() }
            override fun onPlaybackStateChanged(playbackState: Int) { update() }
            override fun onPlayerError(error: PlaybackException) {
                mutable.value=mutable.value.copy(playing=false,error="无法播放此音频，请检查文件格式或重新加载。")
            }
        })
        scope.launch { while(isActive) {update();delay(250)} }
    }
    private fun update() {
        mutable.value=mutable.value.copy(
            playing=player.isPlaying,position=player.currentPosition.coerceAtLeast(0),
            duration=player.duration.coerceAtLeast(0),
        )
    }
    fun toggle(file: File) {
        if(mutable.value.path==file.absolutePath) {
            if(player.isPlaying) player.pause() else {
                if(player.playbackState==Player.STATE_ENDED)player.seekTo(0)
                if(player.playbackState==Player.STATE_IDLE)player.prepare()
                if(foreground)player.play()
            }
        } else {
            player.stop()
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            mutable.value=AudioState(path=file.absolutePath)
            player.prepare()
            if(foreground)player.play()
        }
        update()
    }
    fun seekTo(position: Long) {player.seekTo(position.coerceAtLeast(0));update()}
    fun pause() {player.pause();update()}
    fun stop() {player.stop();player.clearMediaItems();mutable.value=AudioState()}
    override fun onStart(owner:LifecycleOwner) {foreground=true}
    override fun onStop(owner: LifecycleOwner) {
        // Folding/layout recreation must not be treated as the app leaving the foreground.
        if((owner as? ComponentActivity)?.isChangingConfigurations!=true) {foreground=false;pause()}
    }
    fun close() {
        scope.cancel()
        runCatching {app.unregisterReceiver(lockReceiver)}
        player.release()
    }
}
