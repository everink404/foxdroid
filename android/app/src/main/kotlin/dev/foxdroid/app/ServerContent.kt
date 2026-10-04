package dev.foxdroid.app

import android.content.Context
import android.util.AtomicFile
import dev.foxdroid.game.parseChartNotes
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/** HTTP belongs to preparation only; GameActivity receives local private files. */
class ServerContent(private val context: Context, address: String, private val allowed: () -> Boolean) {
    val base = normalize(address)
    private val source = hash(base)
    private val metadata = File(context.filesDir,"server/$source").apply { mkdirs() }
    private val snapshot = AtomicFile(File(metadata,"catalog.json"))
    private val audioRoot = File(context.filesDir,"library/remote-$source").apply { mkdirs() }
    fun cached(): JSONObject? = runCatching { JSONObject(snapshot.openRead().bufferedReader().use { it.readText() }) }.getOrNull()
    fun sync(): JSONObject {
        val server = json("server")
        require(server.getString("apiVersion") == "v1") { "服务器 API 版本不兼容" }
        val catalog = json("catalog"); val songs = catalog.getJSONArray("songs")
        require(songs.length() <= 10000 && catalog.getLong("catalogRevision") >= 0)
        for (i in 0 until songs.length()) { val song = songs.getJSONObject(i); song.getString("id"); song.getString("title") }
        catalog.put("server",server); check(allowed()) { "服务已关闭" }
        val stream = snapshot.startWrite()
        try { stream.write(catalog.toString().toByteArray()); snapshot.finishWrite(stream) }
        catch (e: Throwable) { snapshot.failWrite(stream); throw e }
        return catalog
    }
    private fun revisionRoot(catalog: JSONObject) = File(metadata,"revision-${catalog.getLong("catalogRevision")}").apply { mkdirs() }
    fun detail(catalog: JSONObject, id: String): JSONObject {
        val file = File(revisionRoot(catalog),"song-${hash(id)}.json")
        if (file.isFile) return JSONObject(file.readText())
        val value = json("songs/${segment(id)}"); require(value.getString("id") == id)
        value.getString("title")
        val charts=value.getJSONArray("charts")
        for (i in 0 until charts.length()) { val chart=charts.getJSONObject(i); chart.getString("id"); chart.getString("stepType"); chart.getString("difficulty") }
        val assets=value.getJSONArray("assets")
        for (i in 0 until assets.length()) { val asset=assets.getJSONObject(i); asset.getString("id"); asset.getString("kind"); asset.getString("mimeType"); asset.getLong("size") }
        writeJson(file,value); return value
    }
    fun prepare(catalog: JSONObject, song: JSONObject, chartId: String): String {
        val root = revisionRoot(catalog); val chartFile = File(root,"chart-${hash(chartId)}.json")
        val chart = if (chartFile.isFile) JSONObject(chartFile.readText()) else json("charts/${segment(chartId)}").also {
            require(it.getString("id") == chartId && it.getString("songId") == song.getString("id")); writeJson(chartFile,it)
        }
        require(chart.getString("stepType") == "dance-single") { "仅支持四轨谱面" }
        val timing = chart.getJSONObject("timing"); val notes = chart.getString("noteData")
        require(parseChartNotes(timing.keys().asSequence().associateWith { timing.getString(it) },notes).isNotEmpty()) { "谱面无有效音符" }
        val assets = song.getJSONArray("assets")
        val audio = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("kind") == "music" } ?: error("歌曲没有音频")
        val size = audio.getLong("size"); require(size in 1..512L*1024*1024) { "音频大小不支持" }
        val extension = when(audio.getString("mimeType")) {
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/mpeg" -> "mp3"
            "audio/ogg", "application/ogg" -> "ogg"
            else -> error("音频格式不支持")
        }
        val local = File(audioRoot,"${catalog.getLong("catalogRevision")}-${hash(audio.getString("id"))}.$extension")
        if (!local.isFile || local.length() != size) {
            val used = audioRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            require(used + size <= 512L*1024*1024) { "服务器缓存已满，请清除服务器缓存后重试" }
            val stage = File.createTempFile("download-", ".part",audioRoot)
            try {
                request("assets/${segment(audio.getString("id"))}",size) { input ->
                    stage.outputStream().use { output ->
                        val buffer = ByteArray(65536); var count = 0L; val deadline = System.nanoTime()+120_000_000_000L
                        while (true) {
                            check(allowed()) { "服务已关闭" }; check(System.nanoTime()<deadline) { "下载超时" }
                            val read = input.read(buffer); if (read < 0) break
                            count += read; require(count <= size) { "音频大小与清单不一致" }; output.write(buffer,0,read)
                        }
                        require(count == size) { "音频下载不完整，请重试" }
                    }
                }
                require(stage.renameTo(local)) { "无法保存下载资源" }
            } finally { stage.delete() }
        }
        val ready = File.createTempFile("prepared-server-", ".json",context.cacheDir)
        ready.writeText(JSONObject().put("title",song.getString("title")).put("audio",local.path).put("timing",timing).put("notes",notes).toString())
        return ready.name
    }
    fun clearCache() { metadata.deleteRecursively(); audioRoot.deleteRecursively() }
    private fun writeJson(file: File,value: JSONObject) {
        val atomic=AtomicFile(file); val stream=atomic.startWrite()
        try { stream.write(value.toString().toByteArray()); atomic.finishWrite(stream) }
        catch(e: Throwable) { atomic.failWrite(stream); throw e }
    }
    private fun json(path: String): JSONObject = request(path,8L*1024*1024) { input ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(32768)
        val deadline=System.nanoTime()+30_000_000_000L
        while (true) { check(allowed()) { "服务已关闭" }; check(System.nanoTime()<deadline) { "读取服务器清单超时" }; val read = input.read(buffer); if (read<0) break
            require(output.size()+read <= 8*1024*1024) { "服务器清单过大" }; output.write(buffer,0,read) }
        JSONObject(output.toString("UTF-8"))
    }
    private fun <T> request(path: String, max: Long, read: (java.io.InputStream) -> T): T {
        check(allowed()) { "服务未开启" }
        val connection = URI("$base/api/v1/$path").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout=10000; connection.readTimeout=10000; connection.instanceFollowRedirects=false
        try {
            val status = connection.responseCode
            require(status == 200) { when(status) { 404 -> "歌曲或资源已删除，请同步曲库"; else -> "服务器返回 HTTP $status" } }
            require(connection.contentLengthLong <= max) { "服务器资源过大" }
            return connection.inputStream.use(read)
        } finally { connection.disconnect() }
    }
    companion object {
        fun normalize(address: String): String {
            val uri = URI(address.trim().trimEnd('/'))
            require(uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "请输入 http:// 或 https:// 服务器地址，不含密码、查询或片段" }
            require(uri.path.isNullOrEmpty() || uri.path == "/") { "请输入服务器根地址" }
            return URI(uri.scheme.lowercase(),null,uri.host.lowercase(),uri.port,null,null,null).toString()
        }
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun segment(value: String) = URLEncoder.encode(value,"UTF-8").replace("+","%20")
    }
}
