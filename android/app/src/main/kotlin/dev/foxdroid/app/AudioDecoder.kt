package dev.foxdroid.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedAudio(val file: File, val rate: Int, val frames: Long)

/** Bounded decode chunks go to private disk cache; no decoder/file I/O in the audio callback. */
object AudioDecoder {
    fun decode(path: String, cacheDir: File): DecodedAudio {
        val raw = File.createTempFile("decode-", ".raw", cacheDir)
        val output = File.createTempFile("play-", ".pcm", cacheDir)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString("mime")?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(checkNotNull(format.getString("mime")))
            codec = decoder; decoder.configure(format, null, null, 0); decoder.start()
            val bytes = RandomAccessFile(raw, "rw")
            bytes.use {
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var done = false
            var outputFormat = format
            val deadline = System.nanoTime() + 180_000_000_000L
            while (!done) {
                check(!Thread.currentThread().isInterrupted) { "音频准备已取消" }
                check(System.nanoTime() < deadline) { "音频解码超时" }
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(index))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputFormat = decoder.outputFormat
                else if (index >= 0) {
                    if (info.size > 0) {
                        check(bytes.length()+info.size <= 512L * 1024 * 1024) { "音频缓存超过 512 MiB" }
                        val data = ByteArray(info.size)
                        checkNotNull(decoder.getOutputBuffer(index)).apply { position(info.offset); limit(info.offset+info.size); get(data) }
                        bytes.write(data)
                    }
                    done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(index, false)
                }
            }
            val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(channels in 1..2) { "仅支持单声道或双声道" }
            val rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            require(rate in 8000..192000) { "不支持的采样率 $rate" }
            val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            require(encoding in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT))
            val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val frames = bytes.length()/sampleBytes/channels
            require(frames > 0)
            RandomAccessFile(output, "rw").use { sink ->
                sink.setLength(rate * 2L * 8); sink.seek(sink.length()); bytes.seek(0)
                val input = ByteArray(32768 / (sampleBytes*channels) * (sampleBytes*channels))
                var remaining = frames * sampleBytes * channels
                while (remaining > 0) {
                    check(!Thread.currentThread().isInterrupted) { "音频准备已取消" }
                    val count = minOf(remaining,input.size.toLong()).toInt(); bytes.readFully(input,0,count)
                    val buffer = ByteBuffer.wrap(input,0,count).order(ByteOrder.LITTLE_ENDIAN)
                    val converted = ByteBuffer.allocate(count / sampleBytes / channels * 8).order(ByteOrder.LITTLE_ENDIAN)
                    fun sample() = if (sampleBytes == 4) buffer.float else buffer.short/32768f
                    while (buffer.hasRemaining()) {
                        val left = sample(); val right = if (channels == 2) sample() else left
                        converted.putFloat(left); converted.putFloat(right)
                    }
                    sink.write(converted.array()); remaining -= count
                }
            }
            return DecodedAudio(output, rate, frames+rate*2)
            }
        } catch (e: Throwable) { output.delete(); throw e }
        finally { codec?.release(); extractor.release(); raw.delete() }
    }
}
