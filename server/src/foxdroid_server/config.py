from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _as_bool(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True, slots=True)
class Settings:
    library_path: Path
    data_path: Path
    server_name: str
    scan_on_start: bool
    host: str
    port: int

    @property
    def database_path(self) -> Path:
        return self.data_path / "catalog.sqlite3"

    @classmethod
    def from_env(cls) -> Settings:
        return cls(
            library_path=Path(os.getenv("FOXDROID_LIBRARY_PATH", "./library")).resolve(),
            data_path=Path(os.getenv("FOXDROID_DATA_PATH", "./data")).resolve(),
            server_name=os.getenv("FOXDROID_SERVER_NAME", "FoxDroid Home").strip()
            or "FoxDroid Home",
            scan_on_start=_as_bool(os.getenv("FOXDROID_SCAN_ON_START", "true")),
            host=os.getenv("FOXDROID_HOST", "0.0.0.0"),
            port=int(os.getenv("FOXDROID_PORT", "8080")),
        )
