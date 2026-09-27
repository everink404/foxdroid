from __future__ import annotations

import hashlib
import json
import mimetypes
from pathlib import Path

from .models import AssetRecord, ChartRecord, ScanError, ScanResult, SongRecord
from .simfile import SimfileParseError, parse_simfile


def _stable_id(kind: str, value: str) -> str:
    digest = hashlib.sha256(f"{kind}\0{value}".encode()).hexdigest()
    return f"{kind}_{digest[:24]}"


def _relative(path: Path, root: Path) -> str:
    return path.relative_to(root).as_posix()


def _choose_simfiles(root: Path) -> list[Path]:
    by_folder: dict[Path, list[Path]] = {}
    for path in root.rglob("*"):
        if path.is_file() and path.suffix.lower() in {".sm", ".ssc"}:
            by_folder.setdefault(path.parent, []).append(path)

    selected: list[Path] = []
    for folder in sorted(by_folder, key=lambda item: _relative(item, root).lower()):
        candidates = sorted(
            by_folder[folder],
            key=lambda item: (item.suffix.lower() != ".ssc", item.name.lower()),
        )
        selected.append(candidates[0])
    return selected


def _asset(
    *,
    root: Path,
    song_dir: Path,
    song_id: str,
    kind: str,
    reference: str,
) -> AssetRecord | None:
    if not reference.strip():
        return None
    candidate = (song_dir / reference).resolve()
    try:
        relative_path = _relative(candidate, root)
    except ValueError:
        return None
    if not candidate.is_file():
        return None
    stat = candidate.stat()
    mime_type = mimetypes.guess_type(candidate.name)[0] or "application/octet-stream"
    return AssetRecord(
        id=_stable_id("asset", relative_path.lower()),
        song_id=song_id,
        kind=kind,
        relative_path=relative_path,
        mime_type=mime_type,
        size=stat.st_size,
        modified_ns=stat.st_mtime_ns,
    )


def scan_library(root: Path) -> ScanResult:
    root = root.resolve()
    songs: list[SongRecord] = []
    errors: list[ScanError] = []

    if not root.is_dir():
        errors.append(ScanError(".", "library_unavailable", f"library is unavailable: {root}"))
        catalog_hash = hashlib.sha256(b"[]").hexdigest()
        return ScanResult(tuple(songs), tuple(errors), catalog_hash)

    for simfile_path in _choose_simfiles(root):
        relative_simfile = _relative(simfile_path, root)
        relative_song_dir = _relative(simfile_path.parent, root)
        relative_parts = simfile_path.parent.relative_to(root).parts
        group_name = relative_parts[0] if len(relative_parts) > 1 else "Songs"
        try:
            parsed = parse_simfile(simfile_path, group_name)
            song_id = _stable_id("song", relative_song_dir.lower())
            charts = tuple(
                ChartRecord(
                    id=_stable_id(
                        "chart",
                        f"{relative_simfile.lower()}\0{index}\0{chart.step_type}\0{chart.difficulty}",
                    ),
                    song_id=song_id,
                    step_type=chart.step_type,
                    difficulty=chart.difficulty,
                    meter=chart.meter,
                    credit=chart.credit,
                    description=chart.description,
                    timing=chart.timing,
                    note_data=chart.note_data,
                )
                for index, chart in enumerate(parsed.charts)
            )
            assets = tuple(
                asset
                for asset in (
                    _asset(
                        root=root,
                        song_dir=simfile_path.parent,
                        song_id=song_id,
                        kind="music",
                        reference=parsed.music,
                    ),
                    _asset(
                        root=root,
                        song_dir=simfile_path.parent,
                        song_id=song_id,
                        kind="banner",
                        reference=parsed.banner,
                    ),
                    _asset(
                        root=root,
                        song_dir=simfile_path.parent,
                        song_id=song_id,
                        kind="background",
                        reference=parsed.background,
                    ),
                )
                if asset is not None
            )
            if parsed.music and not any(asset.kind == "music" for asset in assets):
                errors.append(
                    ScanError(relative_simfile, "missing_music", f"missing music: {parsed.music}")
                )

            fingerprint = hashlib.sha256(simfile_path.read_bytes()).hexdigest()
            songs.append(
                SongRecord(
                    id=song_id,
                    relative_path=relative_song_dir,
                    simfile_path=relative_simfile,
                    title=parsed.title,
                    subtitle=parsed.subtitle,
                    artist=parsed.artist,
                    group_name=parsed.group_name,
                    preview_start=parsed.preview_start,
                    preview_length=parsed.preview_length,
                    fingerprint=fingerprint,
                    charts=charts,
                    assets=assets,
                )
            )
        except (OSError, SimfileParseError, ValueError) as exc:
            errors.append(ScanError(relative_simfile, "parse_error", str(exc)))

    catalog_material = [
        {
            "id": song.id,
            "fingerprint": song.fingerprint,
            "assets": [(asset.id, asset.size, asset.modified_ns) for asset in song.assets],
        }
        for song in songs
    ]
    catalog_hash = hashlib.sha256(
        json.dumps(catalog_material, separators=(",", ":"), sort_keys=True).encode()
    ).hexdigest()
    return ScanResult(tuple(songs), tuple(errors), catalog_hash)
