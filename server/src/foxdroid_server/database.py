from __future__ import annotations

import json
import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from .models import ScanResult


class CatalogDatabase:
    def __init__(self, path: Path) -> None:
        self.path = path

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.path)
        try:
            connection.row_factory = sqlite3.Row
            connection.execute("PRAGMA foreign_keys = ON")
            connection.execute("PRAGMA journal_mode = WAL")
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def initialize(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS library_state (
                    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                    catalog_revision INTEGER NOT NULL,
                    catalog_hash TEXT NOT NULL,
                    last_scan_at TEXT,
                    last_scan_status TEXT NOT NULL,
                    song_count INTEGER NOT NULL,
                    error_count INTEGER NOT NULL
                );

                INSERT OR IGNORE INTO library_state
                    (singleton, catalog_revision, catalog_hash, last_scan_status,
                     song_count, error_count)
                VALUES (1, 0, '', 'never', 0, 0);

                CREATE TABLE IF NOT EXISTS songs (
                    id TEXT PRIMARY KEY,
                    relative_path TEXT NOT NULL,
                    simfile_path TEXT NOT NULL,
                    title TEXT NOT NULL,
                    subtitle TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    group_name TEXT NOT NULL,
                    preview_start REAL,
                    preview_length REAL,
                    fingerprint TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS charts (
                    id TEXT PRIMARY KEY,
                    song_id TEXT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                    step_type TEXT NOT NULL,
                    difficulty TEXT NOT NULL,
                    meter INTEGER,
                    credit TEXT NOT NULL,
                    description TEXT NOT NULL,
                    timing_json TEXT NOT NULL,
                    note_data TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS assets (
                    id TEXT PRIMARY KEY,
                    song_id TEXT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                    kind TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    mime_type TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    modified_ns INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS scan_errors (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    relative_path TEXT NOT NULL,
                    error_code TEXT NOT NULL,
                    message TEXT NOT NULL,
                    observed_at TEXT NOT NULL
                );

                CREATE INDEX IF NOT EXISTS charts_song_id ON charts(song_id);
                CREATE INDEX IF NOT EXISTS assets_song_id ON assets(song_id);
                """
            )
            state_columns = {
                row["name"] for row in connection.execute("PRAGMA table_info(library_state)")
            }
            if "last_scan_duration_ms" not in state_columns:
                connection.execute(
                    "ALTER TABLE library_state ADD COLUMN last_scan_duration_ms INTEGER"
                )

    def replace_catalog(
        self, result: ScanResult, scan_duration_ms: int | None = None
    ) -> dict[str, Any]:
        now = datetime.now(UTC).isoformat()
        scan_status = "completed_with_errors" if result.errors else "ok"
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            state = connection.execute(
                "SELECT catalog_revision, catalog_hash FROM library_state WHERE singleton = 1"
            ).fetchone()
            changed = state["catalog_hash"] != result.catalog_hash
            revision = state["catalog_revision"] + (1 if changed else 0)

            if changed:
                connection.execute("DELETE FROM charts")
                connection.execute("DELETE FROM assets")
                connection.execute("DELETE FROM songs")
                for song in result.songs:
                    connection.execute(
                        """
                        INSERT INTO songs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            song.id,
                            song.relative_path,
                            song.simfile_path,
                            song.title,
                            song.subtitle,
                            song.artist,
                            song.group_name,
                            song.preview_start,
                            song.preview_length,
                            song.fingerprint,
                        ),
                    )
                    connection.executemany(
                        "INSERT INTO charts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        [
                            (
                                chart.id,
                                chart.song_id,
                                chart.step_type,
                                chart.difficulty,
                                chart.meter,
                                chart.credit,
                                chart.description,
                                json.dumps(chart.timing, separators=(",", ":"), sort_keys=True),
                                chart.note_data,
                            )
                            for chart in song.charts
                        ],
                    )
                    connection.executemany(
                        "INSERT INTO assets VALUES (?, ?, ?, ?, ?, ?, ?)",
                        [
                            (
                                asset.id,
                                asset.song_id,
                                asset.kind,
                                asset.relative_path,
                                asset.mime_type,
                                asset.size,
                                asset.modified_ns,
                            )
                            for asset in song.assets
                        ],
                    )

            connection.execute("DELETE FROM scan_errors")
            connection.executemany(
                """
                INSERT INTO scan_errors (relative_path, error_code, message, observed_at)
                VALUES (?, ?, ?, ?)
                """,
                [
                    (error.relative_path, error.error_code, error.message, now)
                    for error in result.errors
                ],
            )
            connection.execute(
                """
                UPDATE library_state
                SET catalog_revision = ?, catalog_hash = ?, last_scan_at = ?,
                    last_scan_status = ?, song_count = ?, error_count = ?,
                    last_scan_duration_ms = ?
                WHERE singleton = 1
                """,
                (
                    revision,
                    result.catalog_hash,
                    now,
                    scan_status,
                    len(result.songs),
                    len(result.errors),
                    scan_duration_ms,
                ),
            )
        return {
            "changed": changed,
            "catalogRevision": revision,
            "songCount": len(result.songs),
            "errorCount": len(result.errors),
            "scanDurationMs": scan_duration_ms,
        }

    def state(self) -> dict[str, Any]:
        with self.connect() as connection:
            row = connection.execute("SELECT * FROM library_state WHERE singleton = 1").fetchone()
        return {
            "catalogRevision": row["catalog_revision"],
            "lastScanAt": row["last_scan_at"],
            "lastScanStatus": row["last_scan_status"],
            "songCount": row["song_count"],
            "errorCount": row["error_count"],
            "lastScanDurationMs": row["last_scan_duration_ms"],
        }

    def catalog(self) -> dict[str, Any]:
        with self.connect() as connection:
            state = connection.execute("SELECT * FROM library_state WHERE singleton = 1").fetchone()
            rows = connection.execute(
                """
                SELECT s.*, COUNT(DISTINCT c.id) AS chart_count
                FROM songs s
                LEFT JOIN charts c ON c.song_id = s.id
                GROUP BY s.id
                ORDER BY s.group_name COLLATE NOCASE, s.title COLLATE NOCASE
                """
            ).fetchall()
        return {
            "catalogRevision": state["catalog_revision"],
            "songs": [self._song_summary(row) for row in rows],
        }

    def song(self, song_id: str) -> dict[str, Any] | None:
        with self.connect() as connection:
            song = connection.execute("SELECT * FROM songs WHERE id = ?", (song_id,)).fetchone()
            if song is None:
                return None
            charts = connection.execute(
                """
                SELECT id, step_type, difficulty, meter, credit, description
                FROM charts WHERE song_id = ? ORDER BY meter, difficulty
                """,
                (song_id,),
            ).fetchall()
            assets = connection.execute(
                "SELECT id, kind, mime_type, size FROM assets WHERE song_id = ? ORDER BY kind",
                (song_id,),
            ).fetchall()
        result = self._song_summary(song)
        result["charts"] = [self._camel_chart(row) for row in charts]
        result["assets"] = [self._camel_asset(row) for row in assets]
        return result

    def scan_errors(self) -> list[dict[str, Any]]:
        with self.connect() as connection:
            rows = connection.execute(
                """
                SELECT relative_path, error_code, message, observed_at
                FROM scan_errors ORDER BY relative_path COLLATE NOCASE
                """
            ).fetchall()
        return [
            {
                "relativePath": row["relative_path"],
                "errorCode": row["error_code"],
                "message": row["message"],
                "observedAt": row["observed_at"],
            }
            for row in rows
        ]

    def chart(self, chart_id: str) -> dict[str, Any] | None:
        with self.connect() as connection:
            row = connection.execute("SELECT * FROM charts WHERE id = ?", (chart_id,)).fetchone()
        if row is None:
            return None
        result = self._camel_chart(row)
        result["songId"] = row["song_id"]
        result["timing"] = json.loads(row["timing_json"])
        result["noteData"] = row["note_data"]
        return result

    def asset(self, asset_id: str) -> dict[str, Any] | None:
        with self.connect() as connection:
            row = connection.execute("SELECT * FROM assets WHERE id = ?", (asset_id,)).fetchone()
        return dict(row) if row is not None else None

    @staticmethod
    def _song_summary(row: sqlite3.Row) -> dict[str, Any]:
        result = {
            "id": row["id"],
            "title": row["title"],
            "subtitle": row["subtitle"],
            "artist": row["artist"],
            "groupName": row["group_name"],
            "previewStart": row["preview_start"],
            "previewLength": row["preview_length"],
        }
        if "chart_count" in tuple(row.keys()):
            result["chartCount"] = row["chart_count"]
        return result

    @staticmethod
    def _camel_chart(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": row["id"],
            "stepType": row["step_type"],
            "difficulty": row["difficulty"],
            "meter": row["meter"],
            "credit": row["credit"],
            "description": row["description"],
        }

    @staticmethod
    def _camel_asset(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": row["id"],
            "kind": row["kind"],
            "mimeType": row["mime_type"],
            "size": row["size"],
        }
