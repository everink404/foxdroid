package dev.foxdroid.app

import android.app.Instrumentation
import android.content.Intent
import dev.foxdroid.game.GameInput
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object GameLifecycleChecks {
    fun run(instrumentation: Instrumentation): String {
        val context=instrumentation.targetContext
        val folder=File(context.filesDir,"library/lifecycle-${System.nanoTime()}").apply { mkdirs() }
        val chart=File.createTempFile("prepared-lifecycle-", ".json",context.cacheDir)
        var active: GameActivity?=null
        fun onMain(work: () -> Unit) {
            var failure: Throwable?=null
            instrumentation.runOnMainSync(Runnable { try { work() } catch(e: Throwable) { failure=e } })
            failure?.let { throw it }
        }
        fun field(activity: GameActivity,name: String): Any? = activity.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(activity)
        fun call(activity: GameActivity,name: String) = activity.javaClass.getDeclaredMethod(name).apply { isAccessible=true }.invoke(activity)
        fun awaitReady(activity: GameActivity) {
            val deadline=System.nanoTime()+20_000_000_000L
            var ready=false
            while(!ready && System.nanoTime()<deadline) {
                onMain { ready=field(activity,"decoded") != null }
                if(!ready) Thread.sleep(50)
            }
            check(ready) { "Audio preparation failed" }
        }
        try {
            val music=File(folder,"test.wav"); val bytes=48000*60*2
            val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36+bytes).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(48000).putInt(96000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(bytes)
            RandomAccessFile(music,"rw").use { it.write(header.array()); it.setLength(44L+bytes) }
            chart.writeText(JSONObject().put("title","Lifecycle original fixture").put("audio",music.path)
                .put("timing",JSONObject().put("BPMS","0=120")).put("notes","1000\n0100\n0010\n0001;").toString())
            val first=instrumentation.startActivitySync(Intent(context,GameActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("chartFile",chart.name)) as GameActivity
            active=first; awaitReady(first)
            onMain { call(first,"startGame") }
            Thread.sleep(1500)
            onMain {
                check(field(first,"handle") as Long != 0L)
                @Suppress("UNCHECKED_CAST")
                (field(first,"events") as MutableList<GameInput>).add(GameInput(0,"down",0.0))
            }
            val monitor=instrumentation.addMonitor(GameActivity::class.java.name,null,false)
            onMain { first.recreate() }
            val restored=instrumentation.waitForMonitorWithTimeout(monitor,10000) as? GameActivity ?: error("Activity did not recreate")
            instrumentation.removeMonitor(monitor); active=restored; awaitReady(restored)
            var frame=0L
            onMain {
                check(restored !== first)
                check(field(restored,"paused") == true && field(restored,"handle") == 0L)
                frame=field(restored,"resumeFrame") as Long; check(frame>0)
                check((field(restored,"events") as List<*>).isNotEmpty())
                check((field(restored,"decoded") as DecodedAudio).file.canonicalFile == (field(first,"decoded") as DecodedAudio).file.canonicalFile) { "PCM cache was not reused" }
                call(restored,"startGame")
            }
            Thread.sleep(1000)
            onMain {
                call(restored,"pauseGame")
                check((field(restored,"resumeFrame") as Long)>frame) { "Resume position did not advance" }
            }
            return "PASS: real Activity recreation retains PCM/input/position; restored paused; native resume advances"
        } finally {
            active?.let { activity -> onMain { activity.finish() } }
            instrumentation.waitForIdleSync(); chart.delete(); folder.deleteRecursively()
        }
    }
}
