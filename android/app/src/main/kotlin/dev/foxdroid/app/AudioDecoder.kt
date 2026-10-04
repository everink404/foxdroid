package dev.foxdroid.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedAudio(val pcm: FloatArray, val rate: Int)

/** Fully decodes before opening AAudio. No decoder/file I/O runs in its callback. */
object AudioDecoder {
    fun decode(path: String): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString("mime")?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(checkNotNull(format.getString("mime")))
            codec = decoder; decoder.configure(format, null, null, 0); decoder.start()
            val bytes = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var done = false
            var outputFormat = format
            val deadline = System.nanoTime() + 60_000_000_000L
            while (!done) {
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
                        check(bytes.size()+info.size <= 24 * 1024 * 1024) { "音频超过原型解码内存上限" }
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
            val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            require(encoding in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT))
            val buffer = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val frames = buffer.remaining()/sampleBytes/channels
            require(frames > 0)
            val pcm = FloatArray((frames+rate*2)*2)
            fun sample() = if (sampleBytes == 4) buffer.float else buffer.short/32768f
            for (frame in 0 until frames) {
                val left = sample(); val right = if (channels == 2) sample() else left
                pcm[(frame+rate*2)*2] = left; pcm[(frame+rate*2)*2+1] = right
            }
            return DecodedAudio(pcm, rate)
        } finally { codec?.release(); extractor.release() }
    }
}
