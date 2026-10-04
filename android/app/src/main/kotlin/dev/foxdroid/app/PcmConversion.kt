package dev.foxdroid.app

import java.nio.ByteBuffer

/** Preparation worker only. Never called from AAudio's playback callback. */
object PcmConversion {
    init { System.loadLibrary("fox_audio") }
    external fun convert(input: ByteBuffer,offset: Int,frames: Int,channels: Int,sampleBytes: Int,output: ByteBuffer): Boolean
}
