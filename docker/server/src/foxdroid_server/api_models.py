from __future__ import annotations

from typing import Literal

from pydantic import BaseModel


class LibraryState(BaseModel):
    catalogRevision: int
    lastScanAt: str | None
    lastScanStatus: str
    songCount: int
    errorCount: int
    lastScanDurationMs: int | None
    scanStartedAt: str | None


class HealthResponse(LibraryState):
    status: Literal["ok"]
    version: str


class ServerResponse(LibraryState):
    name: str
    version: str
    apiVersion: Literal["v1"]
    capabilities: list[str]


class ScanResponse(BaseModel):
    changed: bool
    catalogRevision: int
    songCount: int
    errorCount: int
    scanDurationMs: int | None


class CatalogSong(BaseModel):
    id: str
    title: str
    subtitle: str
    artist: str
    groupName: str
    previewStart: float | None
    previewLength: float | None
    chartCount: int


class CatalogResponse(BaseModel):
    catalogRevision: int
    songs: list[CatalogSong]


class ChartSummary(BaseModel):
    id: str
    stepType: str
    difficulty: str
    meter: int | None
    credit: str
    description: str


class AssetSummary(BaseModel):
    id: str
    kind: str
    mimeType: str
    size: int


class SongResponse(BaseModel):
    id: str
    title: str
    subtitle: str
    artist: str
    groupName: str
    previewStart: float | None
    previewLength: float | None
    charts: list[ChartSummary]
    assets: list[AssetSummary]


class ChartResponse(ChartSummary):
    songId: str
    timing: dict[str, str]
    noteData: str


class ScanErrorResponse(BaseModel):
    relativePath: str
    errorCode: str
    message: str
    observedAt: str


class ScanErrorsResponse(BaseModel):
    errors: list[ScanErrorResponse]
