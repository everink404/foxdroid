package dev.foxdroid.app

class NativeAudio {
    companion object { init { System.loadLibrary("fox_audio") } }
    external fun open(pcm: FloatArray, rate: Int, offset: Long): Long
    external fun position(handle: Long, monotonicNanos: Long): Double
    external fun stats(handle: Long): IntArray
    external fun close(handle: Long)
}
