package dev.foxdroid.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.AtomicFile
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class DecodeMetrics(val lookupMs: Long=0,val extractorMs: Long=0,val pipelineMs: Long=0,val convertMs: Long=0,val writeMs: Long=0,val decoder: String="")
data class DecodedAudio(val file: File,val rate: Int,val frames: Long,val cacheHit: Boolean=false,
    val prepareMs: Long=0,val reusable: Boolean=false,val metrics: DecodeMetrics=DecodeMetrics())

/** Bounded preparation pipeline. Native playback still consumes a fixed two-second ring. */
object AudioDecoder {
    private const val BUDGET=512L*1024*1024
    private val preparation=Any()
    private val ownership=Any()
    private val pins=mutableMapOf<String,Int>()
    private fun ms(start: Long)=(System.nanoTime()-start)/1_000_000
    private fun pin(file: File) { val path=file.canonicalPath; pins[path]=(pins[path] ?: 0)+1 }
    fun release(audio: DecodedAudio) {
        if(!audio.reusable) { audio.file.delete(); return }
        synchronized(ownership) {
            val path=audio.file.canonicalPath; val count=(pins[path] ?: 1)-1
            if(count<=0) pins.remove(path) else pins[path]=count
        }
    }
    fun restore(file: File,rate: Int,frames: Long,cacheDir: File): DecodedAudio? = synchronized(ownership) {
        val cached=file.canonicalFile
        val reusable=cached.parentFile==File(cacheDir,"audio-cache").canonicalFile
        require(reusable || cached.parentFile==cacheDir.canonicalFile) { "音频缓存路径无效" }
        if(rate !in 8000..192000 || frames<=0 || !cached.isFile || cached.length()!=frames*8) null
        else { if(reusable) pin(cached); DecodedAudio(cached,rate,frames,cacheHit=true,reusable=reusable) }
    }
    fun decode(path: String,cacheDir: File): DecodedAudio = synchronized(preparation) {
        val start=System.nanoTime()
        check(!Thread.currentThread().isInterrupted) { "音频准备已取消" }
        val root=File(cacheDir,"audio-cache").apply { mkdirs() }
        val hash=MessageDigest.getInstance("SHA-256")
        hash.update("pcm-v2".toByteArray())
        File(path).inputStream().use { input ->
            val chunk=ByteArray(262144)
            while(true) { check(!Thread.currentThread().isInterrupted) { "音频准备已取消" }; val size=input.read(chunk); if(size<0) break; hash.update(chunk,0,size) }
        }
        val key=hash.digest().joinToString("") { "%02x".format(it) }
        val pcm=File(root,"$key.pcm"); val metadata=File(root,"$key.json")
        val lookupMs=ms(start)
        val hit=synchronized(ownership) {
            runCatching {
                val info=JSONObject(AtomicFile(metadata).openRead().bufferedReader().use { it.readText() })
                val rate=info.getInt("rate"); val frames=info.getLong("frames")
                require(rate in 8000..192000 && frames>0 && pcm.isFile && pcm.length()==frames*8)
                pin(pcm); metadata.setLastModified(System.currentTimeMillis())
                DecodedAudio(pcm,rate,frames,true,ms(start),true,DecodeMetrics(lookupMs=lookupMs))
            }.getOrNull()
        }
        if(hit!=null) return@synchronized hit
        synchronized(ownership) { check((pins[pcm.canonicalPath] ?: 0)==0) { "音频缓存正在使用，请退出该局再准备" } }
        val stage=File.createTempFile("pending-", ".pcm",root)
        try {
            val decoded=pipeline(path,stage)
            synchronized(ownership) {
                var used=root.listFiles().orEmpty().filter { it.extension=="pcm" && it!=stage && it!=pcm }.sumOf { it.length() }
                val entries=root.listFiles().orEmpty().filter { it.extension=="pcm" && it!=stage && it!=pcm }
                    .sortedBy { File(root,it.nameWithoutExtension+".json").lastModified() }
                for(entry in entries) {
                    if(used+stage.length()<=BUDGET) break
                    if((pins[entry.canonicalPath] ?: 0)==0) {
                        val size=entry.length()
                        if(entry.delete()) { used-=size; File(root,entry.nameWithoutExtension+".json").delete() }
                    }
                }
                require(used+stage.length()<=BUDGET) { "音频缓存空间不足，请退出其他局后重试" }
                require(stage.renameTo(pcm)) { "无法保存音频缓存" }
                val atomic=AtomicFile(metadata); val output=atomic.startWrite()
                try { output.write(JSONObject().put("rate",decoded.rate).put("frames",decoded.frames).toString().toByteArray()); atomic.finishWrite(output) }
                catch(e: Throwable) { atomic.failWrite(output); throw e }
                pin(pcm)
            }
            decoded.copy(file=pcm,prepareMs=ms(start),reusable=true,metrics=decoded.metrics.copy(lookupMs=lookupMs))
        } finally { stage.delete() }
    }
    /** Fast path for ordinary PCM16 RIFF WAV; all other encodings use the platform decoder. */
    private fun wave(path: String,output: File): DecodedAudio? {
        val started=System.nanoTime()
        RandomAccessFile(path,"r").use { source ->
            if(source.length()<12) return null
            fun tag(): String { val bytes=ByteArray(4); source.readFully(bytes); return String(bytes,Charsets.US_ASCII) }
            fun unsignedInt()=Integer.reverseBytes(source.readInt()).toLong() and 0xffffffffL
            fun unsignedShort()=java.lang.Short.reverseBytes(source.readShort()).toInt() and 0xffff
            if(tag()!="RIFF") return null
            val end=unsignedInt()+8
            if(tag()!="WAVE") return null
            require(end in 12..source.length()) { "WAV 文件不完整" }
            var rate=0; var channels=0; var align=0; var type=0; var bits=0
            var dataOffset=0L; var dataSize=0L
            while(source.filePointer+8<=end) {
                val id=tag(); val size=unsignedInt(); val offset=source.filePointer
                require(offset+size<=end) { "WAV 区块不完整" }
                if(id=="fmt ") {
                    if(size<16) return null
                    type=unsignedShort(); channels=unsignedShort(); rate=unsignedInt().toInt()
                    unsignedInt(); align=unsignedShort(); bits=unsignedShort()
                } else if(id=="data" && dataOffset==0L) { dataOffset=offset; dataSize=size }
                if(rate>0 && dataOffset>0) break
                source.seek(offset+size+(size and 1))
            }
            if(type!=1 || bits!=16 || channels !in 1..2 || rate !in 8000..192000 || align!=channels*2 || dataOffset==0L) return null
            require(dataSize>0 && dataSize%align==0L) { "音频帧不完整" }
            val frames=dataSize/align
            require((frames+rate*2L)*8<=BUDGET) { "展开音频超过 512 MiB" }
            val headerMs=ms(started); val pipelineStart=System.nanoTime()
            var convertNs=0L; var writeNs=0L
            val input=ByteBuffer.allocateDirect(65536)
            val converted=ByteBuffer.allocateDirect(65536); val bytes=ByteArray(65536)
            RandomAccessFile(output,"rw").use { it.setLength(rate*2L*8) }
            source.seek(dataOffset)
            BufferedOutputStream(FileOutputStream(output,true),262144).use { sink ->
                var remaining=frames
                while(remaining>0) {
                    check(!Thread.currentThread().isInterrupted) { "音频准备已取消" }
                    val count=minOf(remaining,8192).toInt()
                    input.clear(); input.limit(count*align)
                    while(input.hasRemaining()) { check(source.channel.read(input)>0) { "WAV 文件不完整" } }
                    val before=System.nanoTime(); converted.clear()
                    check(PcmConversion.convert(input,0,count,channels,2,converted))
                    converted.limit(count*8); converted.get(bytes,0,count*8)
                    convertNs+=System.nanoTime()-before
                    val writing=System.nanoTime(); sink.write(bytes,0,count*8); writeNs+=System.nanoTime()-writing
                    remaining-=count
                }
                val writing=System.nanoTime(); sink.flush(); writeNs+=System.nanoTime()-writing
            }
            return DecodedAudio(output,rate,frames+rate*2L,metrics=DecodeMetrics(extractorMs=headerMs,pipelineMs=ms(pipelineStart),convertMs=convertNs/1_000_000,writeMs=writeNs/1_000_000,decoder="pcm16-wave"))
        }
    }
    private fun pipeline(path: String,output: File): DecodedAudio {
        wave(path,output)?.let { return it }
        val extractor=MediaExtractor(); var codec: MediaCodec?=null; var sink: BufferedOutputStream?=null
        val worker=HandlerThread("audio-prepare").apply { start() }
        var rate=0; var channels=0; var encoding=0; var frames=0L
        var convertNs=0L; var writeNs=0L
        val started=System.nanoTime(); var extractorMs=0L; var pipelineStart=started
        val converted=ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)
        val convertedBytes=ByteArray(65536)
        try {
            extractor.setDataSource(path)
            val track=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString("mime")?.startsWith("audio/")==true } ?: error("音频轨道不存在")
            extractor.selectTrack(track); val format=extractor.getTrackFormat(track); extractorMs=ms(started)
            val decoder=MediaCodec.createDecoderByType(checkNotNull(format.getString("mime")))
            codec=decoder
            val completion=CountDownLatch(1); val failure=AtomicReference<Throwable?>()
            var inputDone=false; var done=false; var outputFormat=format
            fun fail(error: Throwable) { failure.compareAndSet(null,error); done=true; completion.countDown() }
            decoder.setCallback(object: MediaCodec.Callback() {
                override fun onError(codec: MediaCodec,error: MediaCodec.CodecException) { fail(error) }
                override fun onOutputFormatChanged(codec: MediaCodec,format: MediaFormat) { outputFormat=format }
                override fun onInputBufferAvailable(codec: MediaCodec,index: Int) {
                    if(inputDone || done) return
                    try {
                        val buffer=checkNotNull(decoder.getInputBuffer(index)); buffer.clear()
                        val timestamp=extractor.sampleTime
                        // AOSP's MP3 component parses all complete MPEG frames in a buffer.
                        // Other components and packet-oriented formats retain one sample per buffer.
                        val batch=if(decoder.name=="c2.android.mp3.decoder") 32 else 1
                        var bytes=0
                        for(sample in 0 until batch) {
                            val size=extractor.sampleSize
                            if(size<0) break
                            require(size>0 && size<=buffer.capacity()) { "音频帧过大或为空" }
                            if(size>buffer.capacity()-bytes) break
                            buffer.limit(buffer.capacity())
                            val read=extractor.readSampleData(buffer,bytes)
                            check(read.toLong()==size) { "音频帧不完整" }
                            bytes+=read; extractor.advance()
                        }
                        if(bytes==0) { decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true }
                        else decoder.queueInputBuffer(index,0,bytes,timestamp,0)
                    } catch(error: Throwable) { fail(error) }
                }
                override fun onOutputBufferAvailable(codec: MediaCodec,index: Int,info: MediaCodec.BufferInfo) {
                    if(done) return
                    try {
                        if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                            val nextRate=outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val nextChannels=outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val nextEncoding=if(outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            if(rate==0) {
                                rate=nextRate; channels=nextChannels; encoding=nextEncoding
                                require(rate in 8000..192000 && channels in 1..2) { "不支持的采样率或声道数" }
                                require(encoding in setOf(AudioFormat.ENCODING_PCM_16BIT,AudioFormat.ENCODING_PCM_FLOAT)) { "不支持的 PCM 编码" }
                                val writeStart=System.nanoTime()
                                RandomAccessFile(output,"rw").use { it.setLength(rate*2L*8) }
                                sink=BufferedOutputStream(FileOutputStream(output,true),262144); writeNs+=System.nanoTime()-writeStart
                            }
                            require(rate==nextRate && channels==nextChannels && encoding==nextEncoding) { "音频中途改变格式" }
                            val buffer=checkNotNull(decoder.getOutputBuffer(index)).duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset+info.size)
                            val sampleBytes=if(encoding==AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            require(buffer.remaining()%(sampleBytes*channels)==0) { "音频帧不完整" }
                            while(buffer.hasRemaining()) {
                                converted.clear(); val before=System.nanoTime()
                                val count=minOf(buffer.remaining()/(sampleBytes*channels),converted.capacity()/8)
                                check(PcmConversion.convert(buffer,buffer.position(),count,channels,sampleBytes,converted))
                                buffer.position(buffer.position()+count*sampleBytes*channels)
                                converted.limit(count*8); converted.get(convertedBytes,0,count*8); frames+=count
                                convertNs+=System.nanoTime()-before
                                require((frames+rate*2)*8<=BUDGET) { "展开音频超过 512 MiB" }
                                val writing=System.nanoTime(); checkNotNull(sink).write(convertedBytes,0,count*8); writeNs+=System.nanoTime()-writing
                            }
                        }
                        done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                        if(done) completion.countDown()
                    } catch(error: Throwable) { fail(error) }
                    finally { decoder.releaseOutputBuffer(index,false) }
                }
            },Handler(worker.looper))
            decoder.configure(format,null,null,0); pipelineStart=System.nanoTime(); decoder.start()
            check(completion.await(180,TimeUnit.SECONDS)) { "音频解码超时" }
            failure.get()?.let { throw it }
            require(rate>0 && frames>0) { "音频为空" }
            val writing=System.nanoTime(); sink?.close(); sink=null; writeNs+=System.nanoTime()-writing
            return DecodedAudio(output,rate,frames+rate*2,metrics=DecodeMetrics(extractorMs=extractorMs,pipelineMs=ms(pipelineStart),convertMs=convertNs/1_000_000,writeMs=writeNs/1_000_000,decoder=decoder.name))
        } finally {
            worker.quitSafely()
            var interrupted=Thread.interrupted()
            while(worker.isAlive) { try { worker.join() } catch(_: InterruptedException) { interrupted=true } }
            try { codec?.release() } finally {
                try { sink?.close() } finally { extractor.release(); if(interrupted) Thread.currentThread().interrupt() }
            }
        }
    }
}
