package dev.foxdroid.local

import dev.foxdroid.game.parseChartNotes
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

data class LocalChart(val difficulty: String, val meter: Int?, val timing: Map<String, String>, val notes: String)
data class LocalSong(val file: File, val title: String, val artist: String, val music: String,
    val banner: String, val charts: List<LocalChart>)
data class LibraryScan(val songs: List<LocalSong>, val errors: List<String>)

/** File work runs on the import worker, never on a gameplay or audio thread. */
object LocalLibrary {
    const val MAX_BYTES = 512L * 1024 * 1024
    const val MAX_FILES = 10000
    private val timingKeys = setOf("BPMS", "OFFSET", "STOPS", "DELAYS", "WARPS")

    fun safeFile(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.contains('\\') && !relative.contains(':')) { "不安全的路径" }
        val file = File(root, relative).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()) && file != root.canonicalFile) { "路径越界" }
        return file
    }

    fun unzip(input: InputStream, destination: File) {
        var count = 0
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= MAX_FILES) { "曲包文件过多" }
                val target = safeFile(destination, entry.name)
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            val size = zip.read(buffer)
                            if (size < 0) break
                            total += size
                            require(total <= MAX_BYTES) { "曲包解压超过 512 MiB" }
                            output.write(buffer, 0, size)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        require(count > 0) { "ZIP 为空或格式无效" }
    }

    fun parse(file: File): LocalSong {
        require(file.length() <= 4 * 1024 * 1024) { "谱面过大" }
        val text = file.readText().replace(Regex("//[^\\r\\n]*"), "")
        val tags = Regex("#([A-Za-z0-9_]+)\\s*:(.*?);", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).map { it.groupValues[1].uppercase() to it.groupValues[2].trim() }.toList()
        val global = linkedMapOf<String, String>()
        val charts = mutableListOf<LocalChart>()
        var chart: MutableMap<String, String>? = null
        for ((key, value) in tags) {
            if (key == "NOTEDATA") chart = linkedMapOf()
            else if (key == "NOTES") {
                if (chart != null) {
                    val data = chart
                    if (data["STEPSTYPE"] == "dance-single") charts += LocalChart(
                        data["DIFFICULTY"].orEmpty(), data["METER"]?.toIntOrNull(),
                        global.filterKeys { it in timingKeys } + data.filterKeys { it in timingKeys }, value)
                } else {
                    val parts = value.split(':', limit = 6)
                    if (parts.size == 6 && parts[0].trim() == "dance-single") charts += LocalChart(
                        parts[2].trim(), parts[3].trim().toIntOrNull(), global.filterKeys { it in timingKeys }, parts[5])
                }
            } else if (chart != null) chart[key] = value else global[key] = value
        }
        require(global["TITLE"].orEmpty().isNotBlank()) { "缺少标题" }
        require(charts.isNotEmpty()) { "没有 dance-single 谱面" }
        charts.forEach {
            require(it.timing["BPMS"].orEmpty().isNotBlank()) { "缺少 BPM" }
            require(parseChartNotes(it.timing, it.notes).isNotEmpty()) { "谱面没有有效音符" }
        }
        return LocalSong(file, global["TITLE"].orEmpty(), global["ARTIST"].orEmpty(),
            global["MUSIC"].orEmpty(), global["BANNER"].orEmpty(), charts)
    }

    fun scan(root: File): LibraryScan {
        val songs = mutableListOf<LocalSong>()
        val errors = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("sm", "ssc") }
            .groupBy { it.parentFile }.forEach { (_, files) ->
                val preferred = files.filter { it.extension.equals("ssc", true) }.ifEmpty { files }
                preferred.forEach { file ->
                    try { songs += parse(file) }
                    catch (e: Exception) { errors += "${file.relativeTo(root)}：${e.message}" }
                }
            }
        return LibraryScan(songs, errors)
    }

    fun prepare(song: LocalSong, index: Int): File {
        val music = safeFile(song.file.parentFile, song.music)
        require(music.isFile && music.canRead() && music.length() > 0) { "音乐缺失或不可读" }
        require(music.extension.lowercase() in setOf("wav", "ogg", "mp3")) { "不支持的音频格式" }
        require(parseChartNotes(song.charts[index].timing, song.charts[index].notes).isNotEmpty())
        return music
    }
}
