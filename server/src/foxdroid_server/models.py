from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class ParsedChart:
    step_type: str
    difficulty: str
    meter: int | None
    credit: str
    description: str
    timing: dict[str, str]
    note_data: str


@dataclass(frozen=True, slots=True)
class ParsedSong:
    title: str
    subtitle: str
    artist: str
    group_name: str
    music: str
    banner: str
    background: str
    preview_start: float | None
    preview_length: float | None
    timing: dict[str, str]
    charts: tuple[ParsedChart, ...]


@dataclass(frozen=True, slots=True)
class AssetRecord:
    id: str
    song_id: str
    kind: str
    relative_path: str
    mime_type: str
    size: int
    modified_ns: int


@dataclass(frozen=True, slots=True)
class ChartRecord:
    id: str
    song_id: str
    step_type: str
    difficulty: str
    meter: int | None
    credit: str
    description: str
    timing: dict[str, str]
    note_data: str


@dataclass(frozen=True, slots=True)
class SongRecord:
    id: str
    relative_path: str
    simfile_path: str
    title: str
    subtitle: str
    artist: str
    group_name: str
    preview_start: float | None
    preview_length: float | None
    fingerprint: str
    charts: tuple[ChartRecord, ...] = field(default_factory=tuple)
    assets: tuple[AssetRecord, ...] = field(default_factory=tuple)


@dataclass(frozen=True, slots=True)
class ScanError:
    relative_path: str
    error_code: str
    message: str


@dataclass(frozen=True, slots=True)
class ScanResult:
    songs: tuple[SongRecord, ...]
    errors: tuple[ScanError, ...]
    catalog_hash: str
