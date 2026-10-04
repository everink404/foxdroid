package dev.foxdroid.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.foxdroid.local.LibraryScan
import dev.foxdroid.local.LocalChart
import dev.foxdroid.local.LocalLibrary
import dev.foxdroid.local.LocalSong
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Rebuildable metadata only. Imported media and future scores are separate. */
class LibraryIndex(context: Context, name: String = "library-index.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE charts(path TEXT PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported index migration $oldVersion→$newVersion; imported files retained")
    }

    fun refresh(root: File): LibraryScan {
        val db = writableDatabase
        val songs = mutableListOf<LocalSong>()
        val errors = mutableListOf<String>()
        val current = mutableSetOf<String>()
        db.beginTransaction()
        try {
            root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("sm", "ssc") }
                .groupBy { it.parentFile }.forEach { (_, files) ->
                    files.filter { it.extension.equals("ssc", true) }.ifEmpty { files }.forEach { file ->
                        val key = file.relativeTo(root).invariantSeparatorsPath
                        current += key
                        try {
                            val cached = db.query("charts", arrayOf("size", "modified", "payload"), "path=?",
                                arrayOf(key), null, null, null).use { cursor ->
                                if (cursor.moveToFirst() && cursor.getLong(0) == file.length() &&
                                    cursor.getLong(1) == file.lastModified()) cursor.getString(2) else null
                            }
                            val song = cached?.let { runCatching { decode(file, it) }.getOrNull() }
                                ?: LocalLibrary.parse(file)
                            db.insertWithOnConflict("charts", null, ContentValues().apply {
                                put("path", key); put("size", file.length()); put("modified", file.lastModified())
                                put("payload", encode(song))
                            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
                            songs += song
                        } catch (e: IllegalArgumentException) {
                            db.delete("charts", "path=?", arrayOf(key))
                            errors += "$key：${e.message}"
                        } catch (e: java.io.IOException) {
                            db.delete("charts", "path=?", arrayOf(key))
                            errors += "$key：${e.message}"
                        }
                    }
                }
            val stale = db.query("charts", arrayOf("path"), null, null, null, null, null).use { cursor ->
                buildList { while (cursor.moveToNext()) if (cursor.getString(0) !in current) add(cursor.getString(0)) }
            }
            stale.forEach { db.delete("charts", "path=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return LibraryScan(songs.sortedBy { it.title.lowercase() }, errors)
    }

    private fun encode(song: LocalSong): String = JSONObject().apply {
        put("title", song.title); put("artist", song.artist); put("music", song.music); put("banner", song.banner)
        put("charts", JSONArray().apply { song.charts.forEach { chart -> put(JSONObject().apply {
            put("difficulty", chart.difficulty); put("meter", chart.meter ?: JSONObject.NULL)
            put("timing", JSONObject(chart.timing)); put("notes", chart.notes)
        }) } })
    }.toString()

    private fun decode(file: File, payload: String): LocalSong {
        val json = JSONObject(payload)
        val charts = json.getJSONArray("charts")
        return LocalSong(file, json.getString("title"), json.getString("artist"), json.getString("music"),
            json.getString("banner"), (0 until charts.length()).map { i ->
                val chart = charts.getJSONObject(i); val timing = chart.getJSONObject("timing")
                LocalChart(chart.getString("difficulty"), if (chart.isNull("meter")) null else chart.getInt("meter"),
                    timing.keys().asSequence().associateWith { timing.getString(it) }, chart.getString("notes"))
            })
    }
}
