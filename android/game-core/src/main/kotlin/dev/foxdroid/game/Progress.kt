package dev.foxdroid.game

data class Score(val points: Int=0,val combo: Int=0,val maximum: Int=0,val accuracy: Double=0.0)
fun scoreJudgments(results: List<Judgment>): Score {
    val heads=results.filter { it.type != "mine" }.map { it.judgment ?: it.headJudgment ?: "Miss" }
    var combo=0; var maximum=0
    heads.forEach { if(it=="Miss") combo=0 else { combo++; maximum=maxOf(maximum,combo) } }
    val points=heads.map { when(it) { "Perfect" -> 1000; "Great" -> 700; "Good" -> 300; else -> 0 } }.sum()
    val accuracy=if(heads.isEmpty()) 0.0 else heads.sumOf { when(it) { "Perfect" -> 1.0; "Great" -> .8; "Good" -> .5; else -> 0.0 } }/heads.size*100
    return Score(points,combo,maximum,accuracy)
}

data class Progress(val score: Score=Score(),val last: String="",val resolved: Int=0)

/** Publish only after the complete input window closes, using the final evaluator's rules. */
class LiveProgress(notes: List<Note>) {
    private val ordered=notes.sortedWith(compareBy<Note> { it.time }.thenBy { it.lane })
    private var count=0
    private var inputCount=-1
    private var current=Progress()
    fun advance(time: Double,inputs: List<GameInput>): Progress {
        if(!time.isFinite()) return current
        var next=count
        while(next<ordered.size && ordered[next].time+.18<time) next++
        if(next==0 || next==count && inputs.size==inputCount) return current
        val results=evaluateInputs(ordered,inputs.filter { it.time<=time }).take(next)
        val last=results.last()
        current=Progress(scoreJudgments(results),"${last.lane+1} 轨 · ${last.judgment ?: last.headJudgment}",next)
        count=next
        inputCount=inputs.size
        return current
    }
}
