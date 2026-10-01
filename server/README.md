# FoxDroid Content Server

FoxDroid Content Server scans a read-only StepMania-compatible song library, stores a normalized index in SQLite, and exposes the catalog and assets over HTTP. It also serves the built-in Web client entry point.

## Current scope

- `.sm` and standard `.ssc` metadata and chart extraction.
- SQLite catalog with stable song, chart, and asset IDs.
- Health, server information, scan, catalog, song, chart, and asset endpoints.
- Serialized scans with duration/status reporting and conditional catalog requests.
- ETag and byte-range responses for client caching and media preparation.
- A Web vertical slice with library search, song/chart selection, asset preparation,
  four-lane Tap Note rendering, keyboard judgment, and results.
- Docker images intended for `linux/amd64` and `linux/arm64`.

Gameplay supports Tap, Hold, Roll, and Mine with BPM, Stop, Delay, Warp,
keyboard-event timestamps, and local judgment/visual calibration. Sustains and
mines share deterministic input-sequence fixtures with future clients and are
connected to the live Web rendering and result flow. Scoring remains provisional.
Authentication, automatic discovery, and a production-grade administration
interface are not implemented yet.

If the browser tab becomes hidden or its audio clock is suspended during play, the
current run is aborted instead of attempting an unsafe clock resynchronization.

## Run with Docker Compose

Create a `library` directory beside the root `docker-compose.yml`, place song folders inside it, then run:

```shell
docker compose up --build
```

Open `http://localhost:8080/`. The library is mounted read-only and the generated database is stored under `server/data`.

## Run the published image

Pull `montequilla/foxdroid:0.1.0` from Docker Hub, mount your song library at
`/library` read-only and a writable data directory at `/data`, and publish port
`8080`. The image includes the API and Web client. For example, from the project
root on Linux or macOS:

```shell
mkdir -p library server/data
docker run --name foxdroid -p 8080:8080 \
  -v "$PWD/library:/library:ro" -v "$PWD/server/data:/data" \
  montequilla/foxdroid:0.1.0
```

Open `http://localhost:8080/` after the server starts.

## Run for development

```shell
python -m venv .venv
.venv/Scripts/pip install -e ".[dev]"
.venv/Scripts/pytest
.venv/Scripts/foxdroid-server
```

On Linux or macOS, use `.venv/bin/` instead of `.venv/Scripts/`.

For local gameplay verification, generate the deterministic FoxDroid click track:

```shell
.venv/Scripts/python server/tools/generate_demo_audio.py "library/FoxDroid Demo/First Steps/first-steps.wav"
```

The generated WAV contains synthesized clicks only and does not include third-party media.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `FOXDROID_LIBRARY_PATH` | `./library` | Read-only song library root |
| `FOXDROID_DATA_PATH` | `./data` | SQLite and persistent service data |
| `FOXDROID_SERVER_NAME` | `FoxDroid Home` | Display name returned to clients |
| `FOXDROID_SCAN_ON_START` | `true` | Scan the library during startup |
| `FOXDROID_HOST` | `0.0.0.0` | HTTP bind host |
| `FOXDROID_PORT` | `8080` | HTTP port |

## API

- `GET /api/v1/health`
- `GET /api/v1/server`
- `POST /api/v1/admin/scan`
- `GET /api/v1/admin/scan-errors`
- `GET /api/v1/catalog`
- `GET /api/v1/songs/{song_id}`
- `GET /api/v1/charts/{chart_id}`
- `GET /api/v1/assets/{asset_id}`

Interactive OpenAPI documentation is available at `/docs` while the service is running.
The checked-in draft client contract is `shared/api/openapi-v1.json`; tests require
an explicit snapshot update whenever its generated schema changes.
