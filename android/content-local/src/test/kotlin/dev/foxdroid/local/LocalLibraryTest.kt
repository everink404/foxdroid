package dev.foxdroid.local

import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class LocalLibraryTest {
    @Test fun sourceRejectsSongsOutsideRoot() {
        val root = Files.createTempDirectory("fox-source").toFile()
        try {
            val source = LocalContentSource(root)
            val outsider = LocalSong(root.parentFile.resolve("outside.sm"), "Outside", "", "audio.wav", "", emptyList())
            assertFailsWith<IllegalArgumentException> { source.prepare(outsider, 0) }
        } finally { root.deleteRecursively() }
    }
    @Test fun goldenSscPrefersChartTiming() {
        val root = Files.createTempDirectory("fox-local").toFile()
        try {
            val file = root.resolve("song.ssc")
            file.writeText(checkNotNull(javaClass.getResourceAsStream("/Golden Pack/Preferred/song.ssc"))
                .bufferedReader().use { it.readText() })
            val song = LocalLibrary.parse(file)
            assertEquals("Preferred SSC", song.title)
            assertEquals("0=90,4=180", song.charts.single().timing["BPMS"])
            assertFailsWith<IllegalArgumentException> { LocalLibrary.prepare(song, 0) }
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsZipTraversalAndCorruptZip() {
        val root = Files.createTempDirectory("fox-zip").toFile()
        try {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("../escape.sm")); it.write(byteArrayOf(1)); it.closeEntry() }
            assertFailsWith<IllegalArgumentException> { LocalLibrary.unzip(ByteArrayInputStream(bytes.toByteArray()), root) }
            assertFailsWith<IllegalArgumentException> { LocalLibrary.unzip(ByteArrayInputStream(byteArrayOf(1,2)), root) }
            assertFalse(root.resolve("escape.sm").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun isolatesBadSongsAndPreparesGoodSong() {
        val root = Files.createTempDirectory("fox-scan").toFile()
        try {
            root.resolve("good").mkdirs(); root.resolve("bad").mkdirs()
            root.resolve("good/song.sm").writeText("#TITLE:Original;#MUSIC:audio.wav;#BPMS:0=120;#NOTES:dance-single::Easy:1::1000;")
            root.resolve("good/audio.wav").writeBytes(byteArrayOf(1))
            root.resolve("bad/song.sm").writeText("broken")
            val scan = LocalLibrary.scan(root)
            assertEquals(1, scan.songs.size); assertEquals(1, scan.errors.size)
            assertEquals("audio.wav", LocalLibrary.prepare(scan.songs.single(), 0).name)
        } finally { root.deleteRecursively() }
    }
}
