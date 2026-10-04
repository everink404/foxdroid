package dev.foxdroid.content

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ContractTest {
    @Test fun responseFieldsMatchSharedApi() {
        val schemas = checkNotNull(javaClass.getResourceAsStream("/openapi-v1.json"))
            .bufferedReader().use { JsonParser.parseReader(it) }
            .asJsonObject["components"].asJsonObject["schemas"].asJsonObject
        for (type in listOf(CatalogResponse::class.java, CatalogSong::class.java,
            SongResponse::class.java, ChartSummary::class.java,
            ChartResponse::class.java, AssetSummary::class.java)) {
            val expected = schemas[type.simpleName].asJsonObject["properties"]
                .asJsonObject.keySet()
            assertEquals(expected, type.declaredFields.filterNot { it.isSynthetic }
                .map { it.name }.toSet(), type.simpleName)
        }
    }

    @Test fun cacheKeysDoNotAliasSourcesOrSeparators() {
        assertNotEquals(ContentKey("local", "song").cacheKey,
            ContentKey("server", "song").cacheKey)
        assertNotEquals(ContentKey("a:b", "c").cacheKey,
            ContentKey("a", "b:c").cacheKey)
        assertFalse(ClientSettings().serverEnabled)
    }
}
