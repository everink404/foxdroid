from __future__ import annotations

import re
from pathlib import Path

from .models import ParsedChart, ParsedSong

_TAG_PATTERN = re.compile(r"#([A-Za-z0-9_]+)\s*:(.*?);", re.DOTALL)
_TIMING_TAGS = {
    "BPMS",
    "STOPS",
    "DELAYS",
    "WARPS",
    "TIMESIGNATURES",
    "TICKCOUNTS",
    "COMBOS",
    "SPEEDS",
    "SCROLLS",
    "FAKES",
    "LABELS",
    "OFFSET",
}


class SimfileParseError(ValueError):
    """Raised when a simfile cannot produce a playable song record."""


def _read_text(path: Path) -> str:
    raw = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-8", "cp1252"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def _tokens(text: str) -> list[tuple[str, str]]:
    return [
        (match.group(1).upper(), match.group(2).strip()) for match in _TAG_PATTERN.finditer(text)
    ]


def _float_or_none(value: str) -> float | None:
    if not value.strip():
        return None
    try:
        return float(value)
    except ValueError:
        return None


def _int_or_none(value: str) -> int | None:
    if not value.strip():
        return None
    try:
        return int(float(value))
    except ValueError:
        return None


def _song_from_parts(
    metadata: dict[str, str],
    charts: list[ParsedChart],
    group_name: str,
) -> ParsedSong:
    title = metadata.get("TITLE", "").strip()
    if not title:
        raise SimfileParseError("missing #TITLE")
    if not charts:
        raise SimfileParseError("no charts found")

    timing = {name: metadata[name] for name in _TIMING_TAGS if name in metadata}
    return ParsedSong(
        title=title,
        subtitle=metadata.get("SUBTITLE", ""),
        artist=metadata.get("ARTIST", ""),
        group_name=group_name,
        music=metadata.get("MUSIC", ""),
        banner=metadata.get("BANNER", ""),
        background=metadata.get("BACKGROUND", ""),
        preview_start=_float_or_none(metadata.get("SAMPLESTART", "")),
        preview_length=_float_or_none(metadata.get("SAMPLELENGTH", "")),
        timing=timing,
        charts=tuple(charts),
    )


def _parse_sm(tokens: list[tuple[str, str]], group_name: str) -> ParsedSong:
    metadata: dict[str, str] = {}
    note_values: list[str] = []
    for name, value in tokens:
        if name == "NOTES":
            note_values.append(value)
        else:
            metadata[name] = value

    timing = {name: metadata[name] for name in _TIMING_TAGS if name in metadata}
    charts: list[ParsedChart] = []
    for value in note_values:
        parts = value.split(":", 5)
        if len(parts) != 6:
            continue
        charts.append(
            ParsedChart(
                step_type=parts[0].strip(),
                description=parts[1].strip(),
                difficulty=parts[2].strip(),
                meter=_int_or_none(parts[3]),
                credit=parts[1].strip(),
                timing=timing,
                note_data=parts[5].strip(),
            )
        )
    return _song_from_parts(metadata, charts, group_name)


def _parse_ssc(tokens: list[tuple[str, str]], group_name: str) -> ParsedSong:
    metadata: dict[str, str] = {}
    chart_sections: list[dict[str, str]] = []
    current: dict[str, str] | None = None

    for name, value in tokens:
        if name == "NOTEDATA":
            if current is not None:
                chart_sections.append(current)
            current = {}
        elif current is None:
            metadata[name] = value
        else:
            current[name] = value
    if current is not None:
        chart_sections.append(current)

    if not chart_sections:
        return _parse_sm(tokens, group_name)

    song_timing = {name: metadata[name] for name in _TIMING_TAGS if name in metadata}
    charts: list[ParsedChart] = []
    for section in chart_sections:
        note_data = section.get("NOTES", "").strip()
        if not note_data:
            continue
        chart_timing = dict(song_timing)
        chart_timing.update({name: section[name] for name in _TIMING_TAGS if name in section})
        charts.append(
            ParsedChart(
                step_type=section.get("STEPSTYPE", "").strip(),
                difficulty=section.get("DIFFICULTY", "").strip(),
                meter=_int_or_none(section.get("METER", "")),
                credit=section.get("CREDIT", "").strip(),
                description=section.get("DESCRIPTION", "").strip(),
                timing=chart_timing,
                note_data=note_data,
            )
        )
    return _song_from_parts(metadata, charts, group_name)


def parse_simfile(path: Path, group_name: str) -> ParsedSong:
    tokens = _tokens(_read_text(path))
    if not tokens:
        raise SimfileParseError("no tags found")
    if path.suffix.lower() == ".ssc":
        return _parse_ssc(tokens, group_name)
    return _parse_sm(tokens, group_name)
