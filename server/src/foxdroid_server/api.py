from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from pathlib import Path
from time import perf_counter

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles

from . import __version__
from .api_models import (
    CatalogResponse,
    ChartResponse,
    HealthResponse,
    ScanErrorsResponse,
    ScanResponse,
    ServerResponse,
    SongResponse,
)
from .config import Settings
from .database import CatalogDatabase
from .scanner import scan_library


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    database = CatalogDatabase(settings.database_path)
    web_path = Path(__file__).parent / "web"
    scan_lock = asyncio.Lock()
    runtime: dict[str, str | None] = {"scanStartedAt": None}

    async def scan() -> dict[str, object]:
        async with scan_lock:
            runtime["scanStartedAt"] = datetime.now(UTC).isoformat()
            started = perf_counter()
            try:
                result = await asyncio.to_thread(scan_library, settings.library_path)
                duration_ms = round((perf_counter() - started) * 1000)
                return await asyncio.to_thread(
                    database.replace_catalog, result, duration_ms
                )
            finally:
                runtime["scanStartedAt"] = None

    def service_state() -> dict[str, object]:
        result = database.state()
        result["scanStartedAt"] = runtime["scanStartedAt"]
        if scan_lock.locked():
            result["lastScanStatus"] = "running"
        return result

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        settings.data_path.mkdir(parents=True, exist_ok=True)
        database.initialize()
        if settings.scan_on_start:
            await scan()
        yield

    app = FastAPI(
        title="FoxDroid Content Server",
        version=__version__,
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.database = database
    app.state.scan = scan
    app.mount("/static", StaticFiles(directory=web_path), name="static")

    @app.get("/", response_class=HTMLResponse, include_in_schema=False)
    async def web_client() -> HTMLResponse:
        page = web_path / "index.html"
        return HTMLResponse(page.read_text(encoding="utf-8"))

    @app.get("/api/v1/health", response_model=HealthResponse)
    async def health() -> dict[str, object]:
        return {"status": "ok", "version": __version__, **service_state()}

    @app.get("/api/v1/server", response_model=ServerResponse)
    async def server_info() -> dict[str, object]:
        return {
            "name": settings.server_name,
            "version": __version__,
            "apiVersion": "v1",
            "capabilities": ["catalog", "parsed-charts", "assets", "web-client"],
            **service_state(),
        }

    @app.post("/api/v1/admin/scan", response_model=ScanResponse)
    async def trigger_scan() -> dict[str, object]:
        if scan_lock.locked():
            raise HTTPException(status_code=409, detail="scan already in progress")
        return await scan()

    @app.get("/api/v1/admin/scan-errors", response_model=ScanErrorsResponse)
    async def scan_errors() -> dict[str, object]:
        return {"errors": database.scan_errors()}

    @app.get(
        "/api/v1/catalog",
        response_model=CatalogResponse,
        responses={304: {"description": "Catalog revision is unchanged"}},
    )
    async def catalog(request: Request) -> Response:
        payload = database.catalog()
        etag = f'"catalog-{payload["catalogRevision"]}"'
        headers = {"ETag": etag, "Cache-Control": "private, must-revalidate"}
        if request.headers.get("if-none-match") == etag:
            return Response(status_code=304, headers=headers)
        return JSONResponse(payload, headers=headers)

    @app.get("/api/v1/songs/{song_id}", response_model=SongResponse)
    async def song(song_id: str) -> dict[str, object]:
        result = database.song(song_id)
        if result is None:
            raise HTTPException(status_code=404, detail="song not found")
        return result

    @app.get("/api/v1/charts/{chart_id}", response_model=ChartResponse)
    async def chart(chart_id: str) -> dict[str, object]:
        result = database.chart(chart_id)
        if result is None:
            raise HTTPException(status_code=404, detail="chart not found")
        return result

    @app.get(
        "/api/v1/assets/{asset_id}",
        response_class=FileResponse,
        responses={
            200: {"content": {"application/octet-stream": {}}},
            206: {"description": "Requested byte range"},
        },
    )
    async def asset(asset_id: str) -> FileResponse:
        record = database.asset(asset_id)
        if record is None:
            raise HTTPException(status_code=404, detail="asset not found")
        path = (settings.library_path / record["relative_path"]).resolve()
        try:
            path.relative_to(settings.library_path)
        except ValueError as exc:
            raise HTTPException(status_code=404, detail="asset not found") from exc
        if not path.is_file():
            raise HTTPException(status_code=404, detail="asset is unavailable")
        return FileResponse(path, media_type=record["mime_type"])

    return app


app = create_app()
