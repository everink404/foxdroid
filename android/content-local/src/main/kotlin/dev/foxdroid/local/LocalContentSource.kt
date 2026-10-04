package dev.foxdroid.local

import dev.foxdroid.content.ContentKey
import java.io.File

interface SongContentSource {
    val sourceId: String
    fun refresh(): LibraryScan
    fun prepare(song: LocalSong, chartIndex: Int): PreparedLocalSong
}

data class PreparedLocalSong(val key: ContentKey, val chart: LocalChart, val audioFile: File)

class LocalContentSource(private val root: File,
    private val refreshIndex: (File) -> LibraryScan = LocalLibrary::scan) : SongContentSource {
    override val sourceId = "local"
    override fun refresh() = refreshIndex(root)
    override fun prepare(song: LocalSong, chartIndex: Int): PreparedLocalSong {
        require(song.file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "歌曲来源不匹配" }
        val id = song.file.relativeTo(root).invariantSeparatorsPath
        return PreparedLocalSong(ContentKey(sourceId, id), song.charts[chartIndex], LocalLibrary.prepare(song, chartIndex))
    }
}
