package dev.foxdroid.app

import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioPerformanceChecks {
    fun run(test: Instrumentation,format: String?=null): String {
        val inputs=File(test.targetContext.filesDir,"perf-input")
        val work=File(test.targetContext.cacheDir,"perf-${System.nanoTime()}").apply { mkdirs() }
        val report=StringBuilder()
        fun emit(message: String) { report.appendLine(message); test.sendStatus(0,Bundle().apply { putString("stream",message+"\n") }) }
        fun digest(file: File): String {
            val hash=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val bytes=ByteArray(262144); while(true) { val count=input.read(bytes); if(count<0) break; hash.update(bytes,0,count) } }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        fun playback(audio: DecodedAudio) {
            val native=NativeAudio(); val handle=native.open(audio.file.path,audio.rate,0); check(handle!=0L)
            try {
                var first=Double.NaN; val deadline=System.nanoTime()+5_000_000_000L
                while(!first.isFinite() && System.nanoTime()<deadline) { Thread.sleep(20); first=native.position(handle,System.nanoTime()) }
                check(first.isFinite()); Thread.sleep(1000)
                check(native.position(handle,System.nanoTime())-first in .5..1.5)
                check(native.stats(handle)[6]==0)
            } finally { native.close(handle) }
        }
        try {
            val samples=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
            samples.putShort(Short.MIN_VALUE).putShort(0).putShort(Short.MAX_VALUE).putShort(16384)
            val floats=ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN)
            check(PcmConversion.convert(samples,0,4,1,2,floats))
            for(value in listOf(-1f,0f,32767/32768f,.5f)) { check(floats.float==value && floats.float==value) }
            check(!PcmConversion.convert(samples,7,1,2,2,floats))
            check(!PcmConversion.convert(samples,0,5,1,2,floats))
            emit("Native mono conversion and buffer bounds PASS")
            val formats=if(format==null) listOf("wav","mp3","ogg") else listOf(format.also { require(it in listOf("wav","mp3","ogg")) })
            for(extension in formats) {
                val source=File(inputs,"long.$extension"); check(source.isFile)
                val baseDir=File(work,"baseline-$extension").apply { mkdirs() }
                emit("Begin baseline $extension (180s original fixture)")
                val started=System.nanoTime(); val baseline=BaselineAudioDecoder.decode(source.path,baseDir)
                val baselineMs=(System.nanoTime()-started)/1_000_000; val expected=digest(baseline.file)
                emit("Baseline $extension: ${baselineMs}ms, frames=${baseline.frames}")
                for(trial in 1..3) {
                    val cache=File(work,"$extension-$trial").apply { mkdirs() }
                    val cold=AudioDecoder.decode(source.path,cache)
                    try {
                        check(!cold.cacheHit && cold.frames==baseline.frames && cold.rate==baseline.rate)
                        check(digest(cold.file)==expected) { "$extension PCM differs from baseline" }
                        val warm=AudioDecoder.decode(source.path,cache)
                        try {
                            check(warm.cacheHit && warm.file.canonicalFile==cold.file.canonicalFile)
                            emit("$extension trial=$trial cold=${cold.prepareMs}ms warm=${warm.prepareMs}ms metrics=${cold.metrics}")
                        } finally { AudioDecoder.release(warm) }
                        if(trial==1) playback(cold)
                    } finally { AudioDecoder.release(cold) }
                    // A truncated entry must be regenerated rather than accepted by length alone.
                    if(trial==1) {
                        RandomAccessFile(cold.file,"rw").use { it.setLength(8) }
                        val repaired=AudioDecoder.decode(source.path,cache)
                        try { check(!repaired.cacheHit && digest(repaired.file)==expected) }
                        finally { AudioDecoder.release(repaired) }
                        emit("$extension truncated-cache rebuild PASS")
                    }
                }
                baseline.file.delete()
            }
            if("mp3" in formats) {
                for(name in listOf("short-vbr.mp3","short-mono.mp3")) {
                    val source=File(inputs,name); check(source.isFile)
                    val baseline=BaselineAudioDecoder.decode(source.path,work)
                    val decoded=AudioDecoder.decode(source.path,work)
                    try {
                        check(decoded.rate==baseline.rate && decoded.frames==baseline.frames)
                        check(digest(decoded.file)==digest(baseline.file)) { "$name PCM differs from baseline" }
                        emit("$name baseline equality PASS (${decoded.rate}Hz, ${decoded.frames} frames)")
                    } finally { AudioDecoder.release(decoded); baseline.file.delete() }
                }
            }
            if("wav" in formats) {
            val source=File(inputs,"long.wav")
            val cache=File(work,"invalidation").apply { mkdirs() }
            val original=AudioDecoder.decode(source.path,cache)
            try {
                val oldTime=source.lastModified(); val last=source.length()-1
                RandomAccessFile(source,"rw").use { file ->
                    file.seek(last); val value=file.readByte()
                    try {
                        file.seek(last); file.writeByte(value.toInt() xor 1); source.setLastModified(oldTime)
                        // Artificial old cache forces eviction; the active original entry is pinned.
                        val dummy=File(cache,"audio-cache/old.pcm")
                        RandomAccessFile(dummy,"rw").use { it.setLength(512L*1024*1024) }
                        val changed=AudioDecoder.decode(source.path,cache)
                        try {
                            check(!changed.cacheHit && changed.file!=original.file)
                            check(original.file.isFile && !dummy.exists())
                            check(digest(changed.file)!=digest(original.file))
                        } finally { AudioDecoder.release(changed) }
                    } finally { file.seek(last); file.writeByte(value.toInt()); source.setLastModified(oldTime) }
                }
                emit("Content change with same size/time; eviction; pinned playback cache PASS")
            } finally { AudioDecoder.release(original) }
            }
            return "PASS: $formats baseline equality; 3 cold/warm trials each; native playback; cache repair; WAV run also checks invalidation/eviction\n$report"
        } finally { work.deleteRecursively() }
    }
}
