package dev.foxdroid.app

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exercises the real Android SQLite implementation without adding a test framework. */
class IndexInstrumentation : ServerInstrumentation() {
    private var serverMode=false
    private var lifecycleMode=false
    private var performanceMode=false
    private var performanceFormat: String?=null
    override fun onCreate(arguments: Bundle?) { serverMode=arguments?.getString("server")=="true"; lifecycleMode=arguments?.getString("lifecycle")=="true"; performanceMode=arguments?.getString("performance")=="true"; performanceFormat=arguments?.getString("format"); super.onCreate(arguments) }
    override fun onStart() {
        if (serverMode) { super.onStart(); return }
        if (performanceMode) {
            try { finish(Activity.RESULT_OK,Bundle().apply { putString("stream",AudioPerformanceChecks.run(this@IndexInstrumentation,performanceFormat)) }) }
            catch(e: Throwable) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAIL: ${e.stackTraceToString()}") }) }
            return
        }
        if (lifecycleMode) {
            try { finish(Activity.RESULT_OK,Bundle().apply { putString("stream",GameLifecycleChecks.run(this@IndexInstrumentation)) }) }
            catch(e: Throwable) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAIL: ${e.stackTraceToString()}") }) }
            return
        }
        val root = File(targetContext.cacheDir, "index-test-${System.nanoTime()}").apply { mkdirs() }
        val database = "index-test-${System.nanoTime()}.db"
        try {
            val song = File(root, "song.sm")
            fun write(title: String) {
                song.writeText("#TITLE:$title;#BPMS:0=120;#NOTES:dance-single::Easy:1::1000;")
            }
            write("Original")
            LibraryIndex(targetContext, database).use { index ->
                check(index.refresh(root).songs.single().title == "Original")
                check(index.refresh(root).songs.single().title == "Original")
            }
            LibraryIndex(targetContext, database).use { index ->
                check(index.refresh(root).songs.single().title == "Original")
                write("Changed title")
                check(index.refresh(root).songs.single().title == "Changed title")
                song.writeText("broken")
                val broken = index.refresh(root)
                check(broken.songs.isEmpty() && broken.errors.size == 1)
                check(song.delete())
                check(index.refresh(root).let { it.songs.isEmpty() && it.errors.isEmpty() })
            }
            // Three minutes of stereo PCM exceeds the old 24 MiB cap.
            val wav = File(root,"long.wav")
            val dataBytes = 48000 * 180 * 4
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36+dataBytes).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16)
                .put("data".toByteArray()).putInt(dataBytes)
            RandomAccessFile(wav,"rw").use { it.write(header.array()); it.setLength(44L+dataBytes) }
            val decoded = AudioDecoder.decode(wav.path,root)
            check(decoded.rate == 48000 && decoded.frames == 48000L*182)
            check(decoded.file.length() == decoded.frames*8)
            val audio = NativeAudio()
            fun checkPlayback(offset: Long) {
                val handle = audio.open(decoded.file.path,decoded.rate,offset)
                check(handle != 0L)
                try {
                    var first = Double.NaN
                    val deadline = System.nanoTime()+5_000_000_000L
                    while (!first.isFinite() && System.nanoTime()<deadline) {
                        Thread.sleep(20); first = audio.position(handle,System.nanoTime())
                    }
                    check(first.isFinite() && kotlin.math.abs(first-(offset/48000.0-2)) < .2) { "Startup jumped: $first" }
                    Thread.sleep(3000)
                    val next = audio.position(handle,System.nanoTime())
                    check(next-first in 2.5..3.5) { "Clock did not advance: $first -> $next" }
                    check(audio.stats(handle)[6] == 0) { "PCM buffer starved" }
                } finally { audio.close(handle) }
            }
            checkPlayback(0); checkPlayback(48000L*60)
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: SQLite; 180s WAV disk decode; native clock startup/progression/resume; zero PCM starvation") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${e.stackTraceToString()}") })
        } finally {
            root.deleteRecursively(); targetContext.deleteDatabase(database)
        }
    }
}
