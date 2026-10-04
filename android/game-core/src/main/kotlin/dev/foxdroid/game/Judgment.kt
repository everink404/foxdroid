package dev.foxdroid.game

import kotlin.math.abs

data class GameInput(val lane: Int, val action: String, val time: Double)
data class Judgment(val lane: Int, val type: String, val judgment: String? = null,
    val headJudgment: String? = null, val bodyJudgment: String? = null)

fun evaluateInputs(notes: List<Note>, inputs: List<GameInput>): List<Judgment> {
    val events = inputs.filter { it.time.isFinite() && it.lane in 0..3 && it.action in setOf("down", "up") }
        .sortedBy { it.time }
    val downs = events.withIndex().filter { it.value.action == "down" }
    val used = mutableSetOf<Int>()
    fun classify(delta: Double) = when {
        abs(delta) <= .045 -> "Perfect"
        abs(delta) <= .09 -> "Great"
        abs(delta) <= .135 -> "Good"
        else -> "Miss"
    }
    return notes.sortedWith(compareBy<Note> { it.time }.thenBy { it.lane }).map { note ->
        if (note.type == "mine") {
            val stepped = downs.any { it.value.lane == note.lane && abs(it.value.time - note.time) <= .09 }
            val held = events.lastOrNull { it.lane == note.lane && it.time <= note.time }?.action == "down"
            Judgment(note.lane, note.type, judgment = if (stepped || held) "HitMine" else "AvoidMine")
        } else {
            val head = downs.filter { it.index !in used && it.value.lane == note.lane && abs(it.value.time - note.time) <= .18 }
                .minWithOrNull(compareBy<IndexedValue<GameInput>> { abs(it.value.time - note.time) }.thenBy { it.value.time })
            head?.let { used += it.index }
            val judgment = head?.let { classify(it.value.time-note.time) } ?: "Miss"
            if (note.type == "tap") Judgment(note.lane, note.type, judgment = judgment)
            else if (head == null || judgment == "Miss") Judgment(note.lane, note.type, headJudgment = judgment, bodyJudgment = "Missed")
            else {
                val end = checkNotNull(note.endTime)
                var survived = true
                if (note.type == "roll") {
                    var last = head.value.time
                    downs.filter { it.value.lane == note.lane && it.value.time > last && it.value.time <= end }.forEach {
                        if (it.value.time-last > .5) survived = false
                        last = it.value.time
                    }
                    if (end-last > .5) survived = false
                } else {
                    var released: Double? = null
                    events.filter { it.lane == note.lane && it.time > head.value.time && it.time <= end }.forEach {
                        if (it.action == "up" && released == null) released = it.time
                        else if (it.action == "down" && released != null) {
                            if (it.time-checkNotNull(released) > .25) survived = false
                            released = null
                        }
                    }
                    if (released != null && end-checkNotNull(released) > .25) survived = false
                }
                Judgment(note.lane, note.type, headJudgment = judgment, bodyJudgment = if (survived) "Held" else "LetGo")
            }
        }
    }
}
