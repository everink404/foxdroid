package dev.foxdroid.game

data class LaneChange(val lane: Int, val down: Boolean)
class TouchState {
    var pointers: Map<Int, Int> = emptyMap()
        private set
    fun update(next: Map<Int, Int>): List<LaneChange> {
        require(next.values.all { it in 0..3 })
        val before = pointers.values.toSet()
        val after = next.values.toSet()
        pointers = next.toMap()
        return (0..3).mapNotNull { lane ->
            when {
                lane !in before && lane in after -> LaneChange(lane,true)
                lane in before && lane !in after -> LaneChange(lane,false)
                else -> null
            }
        }
    }
}
