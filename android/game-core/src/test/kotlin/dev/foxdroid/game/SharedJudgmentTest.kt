package dev.foxdroid.game

import com.google.gson.JsonParser
import kotlin.test.*

class SharedJudgmentTest {
    @Test fun sharedJudgmentSequences() {
        val root = checkNotNull(javaClass.getResourceAsStream("/judgment-sequences.json"))
            .bufferedReader().use { JsonParser.parseReader(it) }.asJsonObject
        for (case in root["cases"].asJsonArray) {
            val value = case.asJsonObject
            val notes = value["notes"].asJsonArray.map { it.asJsonObject.let { n ->
                Note(n["lane"].asInt, 0.0, n["time"].asDouble, n["type"].asString,
                    endTime = if (n.has("endTime")) n["endTime"].asDouble else null)
            } }
            val inputs = value["inputs"].asJsonArray.map { it.asJsonObject.let { e ->
                GameInput(e["lane"].asInt, e["action"].asString, e["time"].asDouble)
            } }
            val results = evaluateInputs(notes, inputs)
            value["expected"].asJsonArray.forEachIndexed { i, expected ->
                val e = expected.asJsonObject; val actual = results[i]
                if (e.has("judgment")) assertEquals(e["judgment"].asString, actual.judgment, value["name"].asString)
                if (e.has("headJudgment")) assertEquals(e["headJudgment"].asString, actual.headJudgment)
                if (e.has("bodyJudgment")) assertEquals(e["bodyJudgment"].asString, actual.bodyJudgment)
            }
        }
    }
}
