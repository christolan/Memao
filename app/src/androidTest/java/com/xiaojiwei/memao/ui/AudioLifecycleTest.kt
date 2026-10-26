package com.xiaojiwei.memao.ui

import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.xiaojiwei.memao.media.ForegroundAudio
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioLifecycleTest {
    @get:Rule val activity=ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private lateinit var audio:ForegroundAudio
    private lateinit var owner:Owner
    private lateinit var file:File

    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        file=File.createTempFile("memao_audio_",".wav",context.cacheDir)
        writeWave(file)
        instrumentation.runOnMainSync {
            audio=ForegroundAudio(context)
            owner=Owner()
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.lifecycle.addObserver(audio)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    @After fun cleanup() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        instrumentation.runOnMainSync {
            if(::owner.isInitialized&&::audio.isInitialized)owner.lifecycle.removeObserver(audio)
            if(::audio.isInitialized)audio.close()
        }
        if(::file.isInitialized)file.delete()
    }

    @Test fun backgroundPausesKeepsPositionAndRequiresManualResume() {
        instrumentation.runOnMainSync {audio.toggle(file)}
        waitFor {audio.state.value.playing&&audio.state.value.position>350}
        instrumentation.runOnMainSync {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        val paused=settledPausedPosition()
        SystemClock.sleep(650)
        assertTrue("后台不应继续播放：暂停稳定在 $paused，当前 ${audio.state.value.position}",abs(audio.state.value.position-paused)<=25)
        instrumentation.runOnMainSync {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        SystemClock.sleep(350)
        assertFalse("返回前台不应自动续播",audio.state.value.playing)
        instrumentation.runOnMainSync {audio.toggle(file)}
        waitFor {audio.state.value.playing&&audio.state.value.position>paused+200}
        instrumentation.runOnMainSync {audio.seekTo(5_000);audio.pause()}
        assertTrue(abs(audio.state.value.position-5_000)<100)
    }

    @Test fun screenOffPausesAndWakeDoesNotAutoPlay() {
        instrumentation.runOnMainSync {audio.toggle(file)}
        waitFor {audio.state.value.playing&&audio.state.value.position>350}
        shell("input keyevent 223")
        val paused=settledPausedPosition()
        SystemClock.sleep(500)
        assertTrue("锁屏不应继续播放：暂停稳定在 $paused，当前 ${audio.state.value.position}",abs(audio.state.value.position-paused)<=25)
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        SystemClock.sleep(350)
        assertFalse("锁屏后恢复不应自动续播",audio.state.value.playing)
    }

    @Test fun lateDownloadCompletionCannotStartPlaybackWhileBackgrounded() {
        instrumentation.runOnMainSync {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            audio.toggle(file)
        }
        SystemClock.sleep(650)
        assertFalse(audio.state.value.playing)
        instrumentation.runOnMainSync {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        SystemClock.sleep(300)
        assertFalse(audio.state.value.playing)
    }

    private fun shell(command:String) {
        instrumentation.uiAutomation.executeShellCommand(command).use {fd->java.io.FileInputStream(fd.fileDescriptor).use {it.readBytes()}}
    }
    private fun waitFor(condition:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+10_000
        while(!condition()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50)
        assertTrue("音频状态未在等待时间内满足",condition())
    }
    private fun settledPausedPosition():Long {
        // Media3's pause acknowledgement can settle the rendered position asynchronously.
        // Require five stable samples (covering multiple player polls), then verify it stays frozen.
        var stable=0
        var last=-1L
        val deadline=SystemClock.elapsedRealtime()+4_000
        while(SystemClock.elapsedRealtime()<deadline) {
            val current=audio.state.value
            stable=if(!current.playing&&last>=0&&abs(current.position-last)<=1)stable+1 else 0
            last=current.position
            if(stable>=5)return current.position
            SystemClock.sleep(100)
        }
        fail("暂停后的音频进度没有稳定：${audio.state.value}")
        return -1
    }
    private class Owner:LifecycleOwner {
        val registry=LifecycleRegistry(this)
        override val lifecycle:Lifecycle get()=registry
    }
    private fun writeWave(file:File) {
        val rate=16_000
        val samples=rate*30
        DataOutputStream(BufferedOutputStream(file.outputStream())).use {out->
            fun leInt(value:Int)=out.writeInt(Integer.reverseBytes(value))
            fun leShort(value:Int)=out.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
            out.writeBytes("RIFF");leInt(36+samples*2);out.writeBytes("WAVE")
            out.writeBytes("fmt ");leInt(16);leShort(1);leShort(1);leInt(rate);leInt(rate*2);leShort(2);leShort(16)
            out.writeBytes("data");leInt(samples*2)
            repeat(samples){index->leShort((sin(2*PI*440*index/rate)*1500).toInt())}
        }
    }
}
