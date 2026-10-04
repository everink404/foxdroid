package dev.foxdroid.app

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import java.io.File

/** Exercises the real Android SQLite implementation without adding a test framework. */
class IndexInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val root = File(targetContext.cacheDir, "index-test-${System.nanoTime()}").apply { mkdirs() }
        val database = "index-test-${System.nanoTime()}.db"
        try {
            val song = File(root, "song.sm")
            fun write(title: String) {
                song.writeText("#TITLE:$title;#BPMS:0=120;#NOTES:dance-single::Easy:1::1000;")
            }
            write("Original")
            LibraryIndex(targetContext, database).use { index ->
                check(index.refresh(root).songs.single().title == "Original")
                check(index.refresh(root).songs.single().title == "Original")
            }
            LibraryIndex(targetContext, database).use { index ->
                check(index.refresh(root).songs.single().title == "Original")
                write("Changed title")
                check(index.refresh(root).songs.single().title == "Changed title")
                song.writeText("broken")
                val broken = index.refresh(root)
                check(broken.songs.isEmpty() && broken.errors.size == 1)
                check(song.delete())
                check(index.refresh(root).let { it.songs.isEmpty() && it.errors.isEmpty() })
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: SQLite create/cache/reopen/change/bad-file/delete") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${e.stackTraceToString()}") })
        } finally {
            root.deleteRecursively(); targetContext.deleteDatabase(database)
        }
    }
}
