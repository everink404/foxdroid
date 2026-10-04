package dev.foxdroid.content

/** IDs are scoped to a source; equal titles or remote IDs never merge sources. */
data class ContentKey(val sourceId: String, val id: String) {
    init { require(sourceId.isNotBlank() && id.isNotBlank()) }
    val cacheKey: String get() = "${sourceId.length}:$sourceId${id.length}:$id"
}

enum class SourceKind { LOCAL, SERVER }
data class ContentSource(val id: String, val kind: SourceKind, val name: String)
data class ChartSummary(val id: String, val stepType: String, val difficulty: String,
    val meter: Int?, val credit: String, val description: String)
data class AssetSummary(val id: String, val kind: String, val mimeType: String, val size: Long)
data class CatalogSong(val id: String, val title: String, val subtitle: String,
    val artist: String, val groupName: String, val previewStart: Double?,
    val previewLength: Double?, val chartCount: Int)
data class CatalogResponse(val catalogRevision: Long, val songs: List<CatalogSong>)
data class SongResponse(val id: String, val title: String, val subtitle: String,
    val artist: String, val groupName: String, val previewStart: Double?,
    val previewLength: Double?, val charts: List<ChartSummary>, val assets: List<AssetSummary>)
data class ChartResponse(val id: String, val songId: String, val stepType: String,
    val difficulty: String, val meter: Int?, val credit: String, val description: String,
    val timing: Map<String, String>, val noteData: String)
data class PreparedSong(val key: ContentKey, val chart: ChartResponse, val audioPath: String)
data class ClientSettings(val serverEnabled: Boolean = false, val serverAddress: String = "",
    val audioOffsetMs: Int = 0, val visualOffsetMs: Int = 0, val inputOffsetMs: Int = 0)
data class ContentError(val code: String, val message: String, val key: ContentKey? = null)
