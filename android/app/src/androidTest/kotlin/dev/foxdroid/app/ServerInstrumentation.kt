package dev.foxdroid.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Run against the repository's actual server with an original fixture library. */
open class ServerInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args=arguments ?: Bundle(); start() }
    override fun onStart() {
        try {
            check(runCatching { ServerContent.normalize("file:///tmp") }.isFailure)
            check(runCatching { ServerContent.normalize("http://user:password@localhost") }.isFailure)
            var allowed=false
            val client=ServerContent(targetContext,args.getString("base") ?: "http://127.0.0.1:18080") { allowed }
            check(runCatching { client.sync() }.exceptionOrNull()?.message == "服务未开启")
            allowed=true
            val catalog=client.sync(); check(client.cached()?.getLong("catalogRevision") == catalog.getLong("catalogRevision"))
            val first=catalog.getJSONArray("songs").getJSONObject(0)
            check(runCatching { client.detail(catalog,"missing-test-id") }.isFailure)
            val song=client.detail(catalog,first.getString("id"))
            val chartId=song.getJSONArray("charts").getJSONObject(0).getString("id")
            val readyFile=File(targetContext.cacheDir,client.prepare(catalog,song,chartId))
            val prepared=JSONObject(readyFile.readText()); val audio=File(prepared.getString("audio"))
            val expected=args.getString("sha256")
            if (expected != null) {
                val hash=MessageDigest.getInstance("SHA-256").digest(audio.readBytes()).joinToString("") { "%02x".format(it) }
                check(hash==expected) { "Downloaded media differs from fixture" }
            }
            java.io.RandomAccessFile(audio,"rw").use { it.setLength(1) }
            val restored=File(targetContext.cacheDir,client.prepare(catalog,song,chartId))
            check(audio.length()>1) // Incomplete cached media must be downloaded again.
            allowed=false
            check(client.detail(catalog,first.getString("id")).getString("id")==first.getString("id"))
            val offline=File(targetContext.cacheDir,client.prepare(catalog,song,chartId))
            check(JSONObject(offline.readText()).getString("audio")==audio.path)
            check(runCatching { client.sync() }.isFailure)
            readyFile.delete(); restored.delete(); offline.delete()
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream","PASS: address validation; disabled guard; server/catalog/detail/chart/media; 404; incomplete cache retry; offline preparation") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAIL: ${e.stackTraceToString()}") })
        }
    }
}
