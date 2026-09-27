from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase

from test_simfile import SM_CONTENT

from foxdroid_server.database import CatalogDatabase
from foxdroid_server.scanner import scan_library


class ScannerDatabaseTests(TestCase):
    def test_scan_is_indexed_and_unchanged_scan_keeps_revision(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            library = root / "library"
            song_dir = library / "Test Pack" / "Test Song"
            song_dir.mkdir(parents=True)
            (song_dir / "test.sm").write_text(SM_CONTENT, encoding="utf-8")
            (song_dir / "test.ogg").write_bytes(b"not-real-audio")

            database = CatalogDatabase(root / "data" / "catalog.sqlite3")
            database.initialize()

            first = database.replace_catalog(scan_library(library))
            second = database.replace_catalog(scan_library(library))

            self.assertTrue(first["changed"])
            self.assertFalse(second["changed"])
            self.assertEqual(first["catalogRevision"], second["catalogRevision"])
            catalog = database.catalog()
            self.assertEqual(len(catalog["songs"]), 1)
            self.assertEqual(catalog["songs"][0]["chartCount"], 1)

            song = database.song(catalog["songs"][0]["id"])
            self.assertEqual(song["assets"][0]["kind"], "music")

    def test_broken_song_does_not_block_valid_song(self) -> None:
        with TemporaryDirectory() as directory:
            library = Path(directory) / "library"
            valid = library / "Pack" / "Valid"
            broken = library / "Pack" / "Broken"
            valid.mkdir(parents=True)
            broken.mkdir(parents=True)
            (valid / "valid.sm").write_text(SM_CONTENT, encoding="utf-8")
            (valid / "test.ogg").write_bytes(b"not-real-audio")
            (broken / "broken.sm").write_text("#ARTIST:Nobody;", encoding="utf-8")

            result = scan_library(library)

            self.assertEqual(len(result.songs), 1)
            self.assertEqual(len(result.errors), 1)
            self.assertEqual(result.errors[0].error_code, "parse_error")
