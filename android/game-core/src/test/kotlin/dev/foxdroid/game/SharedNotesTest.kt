package dev.foxdroid.game

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedNotesTest {
    @Test fun sharedChartVectors() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/chart-notes.json"))
        val root = stream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        assertEquals(1, root["schemaVersion"].asInt)
        for (case in root["cases"].asJsonArray) {
            val value = case.asJsonObject
            val chart = value["chart"].asJsonObject
            val timing = chart["timing"].asJsonObject.entrySet().associate { it.key to it.value.asString }
            val notes = parseChartNotes(timing, chart["noteData"].asString)
            val expected = value["expected"].asJsonArray
            val name = value["name"].asString
            assertEquals(expected.size(), notes.size, name)
            expected.forEachIndexed { index, element ->
                val target = element.asJsonObject
                val note = notes[index]
                assertEquals(target["lane"].asInt, note.lane, name)
                assertEquals(target["type"].asString, note.type, name)
                assertEquals(target["beat"].asDouble, note.beat, 1e-7, name)
                assertEquals(target["time"].asDouble, note.time, 1e-7, name)
                if (target.has("endBeat")) {
                    assertEquals(target["endBeat"].asDouble, checkNotNull(note.endBeat), 1e-7, name)
                    assertEquals(target["endTime"].asDouble, checkNotNull(note.endTime), 1e-7, name)
                } else assertTrue(note.endBeat == null && note.endTime == null, name)
            }
        }
    }
}
