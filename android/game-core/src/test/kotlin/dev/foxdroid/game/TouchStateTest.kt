package dev.foxdroid.game

import kotlin.test.*
class TouchStateTest {
    @Test fun fourPointersMoveReleaseAndCancel() {
        val state = TouchState()
        assertEquals((0..3).map { LaneChange(it,true) },state.update(mapOf(10 to 0,20 to 1,30 to 2,40 to 3)))
        assertEquals(emptyList(),state.update(mapOf(10 to 0,20 to 1,30 to 2,40 to 3,50 to 0)))
        assertEquals(emptyList(),state.update(mapOf(20 to 1,30 to 2,40 to 3,50 to 0)))
        assertEquals(listOf(LaneChange(0,false)),state.update(mapOf(20 to 1,30 to 2,40 to 3)))
        assertEquals(listOf(LaneChange(1,false),LaneChange(2,false),LaneChange(3,false)),state.update(emptyMap()))
    }
    @Test fun moveToAnotherLaneReleasesOldLane() {
        val state=TouchState(); state.update(mapOf(1 to 0))
        assertEquals(listOf(LaneChange(0,false),LaneChange(3,true)),state.update(mapOf(1 to 3)))
    }
}
