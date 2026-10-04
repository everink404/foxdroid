package dev.foxdroid.game

import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressTest {
    @Test fun lateDeliveredTimestampCorrectsProgress() {
        val notes=listOf(Note(0,0.0,1.0,"tap"))
        val live=LiveProgress(notes)
        assertEquals(0,live.advance(1.3,emptyList()).score.points)
        assertEquals(1000,live.advance(1.4,listOf(GameInput(0,"down",1.0))).score.points)
    }
    @Test fun waitsForFullWindowAndMatchesSettlement() {
        val notes=listOf(Note(0,0.0,1.0,"tap"),Note(1,1.0,2.0,"tap"),Note(2,2.0,3.0,"tap"))
        val inputs=listOf(GameInput(0,"down",1.0),GameInput(1,"down",2.08))
        val live=LiveProgress(notes)
        assertEquals(0,live.advance(1.1,inputs).resolved)
        assertEquals(1000,live.advance(1.2,inputs).score.points)
        assertEquals(2,live.advance(2.2,inputs).score.combo)
        assertEquals(scoreJudgments(evaluateInputs(notes,inputs)),live.advance(4.0,inputs).score)
        assertEquals(0,live.advance(4.1,inputs).score.combo)
        assertEquals(2,live.advance(4.2,inputs).score.maximum)
    }
    @Test fun jumpSustainAndMineShareHeadScore() {
        val notes=listOf(Note(0,0.0,1.0,"hold",2.0,2.0),Note(1,0.0,1.0,"tap"),Note(2,1.0,1.5,"mine"))
        val inputs=listOf(GameInput(0,"down",1.0),GameInput(1,"down",1.0),GameInput(2,"down",1.5),GameInput(0,"up",2.0))
        val live=LiveProgress(notes)
        assertEquals(2000,live.advance(1.2,inputs).score.points)
        assertEquals(2,live.advance(2.3,inputs).score.combo)
        assertEquals(scoreJudgments(evaluateInputs(notes,inputs)),live.advance(3.0,inputs).score)
    }
}
