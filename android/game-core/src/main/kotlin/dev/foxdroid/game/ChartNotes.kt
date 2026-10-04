package dev.foxdroid.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val EPSILON = 1e-7
data class Note(val lane: Int, val beat: Double, val time: Double, val type: String,
    val endBeat: Double? = null, val endTime: Double? = null)

/** Mirrors the version-1 shared Web timing contract, including pauses at warp beats. */
class TimingMap(timing: Map<String, String>) {
    private fun pairs(value: String?): Map<Double, Double> = value.orEmpty().split(',')
        .mapNotNull { entry ->
            val parts = entry.split('=')
            val beat = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
            val amount = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (beat != null && beat.isFinite() && amount != null && amount.isFinite() && amount > 0)
                beat to amount else null
        }.toMap().toSortedMap()
    private val bpms = pairs(timing["BPMS"]).ifEmpty { mapOf(0.0 to 120.0) }
    private val stops = pairs(timing["STOPS"])
    private val delays = pairs(timing["DELAYS"])
    private val warps = pairs(timing["WARPS"])
    private val events = (bpms.keys + stops.keys + delays.keys + warps.keys).filter { it >= 0 }.sorted()

    fun beatToSeconds(target: Double): Double {
        require(target.isFinite() && target >= 0)
        var time = 0.0
        var lastBeat = 0.0
        var bpm = bpms.entries.lastOrNull { it.key <= 0 }?.value ?: 120.0
        var warpUntil = Double.NEGATIVE_INFINITY
        for (beat in events) {
            if (beat > target + EPSILON) break
            val start = max(lastBeat, min(beat, warpUntil))
            time += max(0.0, beat - start) * 60 / bpm
            lastBeat = beat
            bpm = bpms[beat] ?: bpm
            time += (delays[beat] ?: 0.0) + (stops[beat] ?: 0.0)
            warps[beat]?.let { warpUntil = max(warpUntil, beat + it) }
        }
        return time + max(0.0, target - max(lastBeat, min(target, warpUntil))) * 60 / bpm
    }

    fun isWarpedBeat(beat: Double): Boolean {
        if ((stops.keys + delays.keys).any { abs(it - beat) <= EPSILON }) return false
        return warps.any { (start, length) -> start <= beat + EPSILON && beat < start + length - EPSILON }
    }
}

fun parseChartNotes(timing: Map<String, String>, noteData: String): List<Note> {
    val timeline = TimingMap(timing)
    val offset = timing["OFFSET"]?.toDoubleOrNull() ?: 0.0
    require(offset.isFinite())
    val notes = mutableListOf<Note>()
    val active = mutableMapOf<Int, Note>()
    noteData.split(',').forEachIndexed { measureIndex, measure ->
        val rows = measure.lines().map { it.trim() }.filter { it.matches(Regex("[0-9A-Za-z]+")) }
        rows.forEachIndexed { rowIndex, row ->
            val beat = measureIndex * 4.0 + rowIndex * 4.0 / rows.size
            val time = timeline.beatToSeconds(beat) - offset
            row.take(4).forEachIndexed { lane, symbol ->
                if (symbol == '3') {
                    active.remove(lane)?.let { head ->
                        if (beat > head.beat && time > head.time) notes += head.copy(endBeat = beat, endTime = time)
                    }
                } else if (!timeline.isWarpedBeat(beat)) {
                    when (symbol.uppercaseChar()) {
                        '1' -> notes += Note(lane, beat, time, "tap")
                        'M' -> notes += Note(lane, beat, time, "mine")
                        '2', '4' -> if (lane !in active) active[lane] = Note(lane, beat, time, if (symbol == '2') "hold" else "roll")
                    }
                }
            }
        }
    }
    return notes.sortedWith(compareBy<Note> { it.time }.thenBy { it.lane })
}
