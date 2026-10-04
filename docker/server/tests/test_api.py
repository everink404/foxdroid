import json
from pathlib import Path
from tempfile import TemporaryDirectory

from fastapi.testclient import TestClient
from test_simfile import SM_CONTENT

from foxdroid_server.api import create_app
from foxdroid_server.config import Settings


def _settings(root: Path, library: Path) -> Settings:
    return Settings(
        library_path=library,
        data_path=root / "data",
        server_name="Test Server",
        scan_on_start=True,
        host="127.0.0.1",
        port=8080,
    )


def test_http_catalog_chart_and_asset_flow() -> None:
    with TemporaryDirectory() as directory:
        root = Path(directory)
        library = root / "library"
        song_dir = library / "Test Pack" / "Test Song"
        song_dir.mkdir(parents=True)
        (song_dir / "test.sm").write_text(SM_CONTENT, encoding="utf-8")
        (song_dir / "test.ogg").write_bytes(b"test-audio-body")

        with TestClient(create_app(_settings(root, library))) as client:
            home = client.get("/")
            assert home.status_code == 200
            assert "FoxDroid" in home.text
            assert client.get("/static/app.css").status_code == 200
            script = client.get("/static/app.js")
            assert script.status_code == 200
            assert "function judge" in script.text
            assert client.get("/static/core.mjs").status_code == 200
            assert client.get("/static/gameplay.mjs").status_code == 200

            health = client.get("/api/v1/health").json()
            assert health["status"] == "ok"
            assert health["songCount"] == 1
            assert health["lastScanDurationMs"] >= 0
            assert health["scanStartedAt"] is None

            server = client.get("/api/v1/server").json()
            assert server["name"] == "Test Server"
            assert "web-client" in server["capabilities"]

            catalog_response = client.get("/api/v1/catalog")
            catalog = catalog_response.json()
            assert len(catalog["songs"]) == 1
            catalog_etag = catalog_response.headers["etag"]
            unchanged = client.get(
                "/api/v1/catalog", headers={"If-None-Match": catalog_etag}
            )
            assert unchanged.status_code == 304
            assert unchanged.content == b""
            song_id = catalog["songs"][0]["id"]

            song = client.get(f"/api/v1/songs/{song_id}").json()
            assert song["title"] == "Test Song"
            assert song["assets"][0]["mimeType"] == "audio/ogg"
            chart_id = song["charts"][0]["id"]
            asset_id = song["assets"][0]["id"]

            chart = client.get(f"/api/v1/charts/{chart_id}").json()
            assert chart["songId"] == song_id
            assert chart["noteData"].startswith("0000")

            asset = client.get(f"/api/v1/assets/{asset_id}")
            assert asset.status_code == 200
            assert asset.content == b"test-audio-body"
            assert "etag" in asset.headers

            partial = client.get(
                f"/api/v1/assets/{asset_id}", headers={"Range": "bytes=0-3"}
            )
            assert partial.status_code == 206
            assert partial.content == b"test"
            assert partial.headers["content-range"] == "bytes 0-3/15"

            revision = catalog["catalogRevision"]
            scan = client.post("/api/v1/admin/scan").json()
            assert scan["changed"] is False
            assert scan["catalogRevision"] == revision
            assert scan["scanDurationMs"] >= 0

            assert client.get("/api/v1/songs/not-found").status_code == 404
            assert client.get("/api/v1/charts/not-found").status_code == 404
            assert client.get("/api/v1/assets/not-found").status_code == 404


def test_missing_library_is_reported_without_creating_it() -> None:
    with TemporaryDirectory() as directory:
        root = Path(directory)
        library = root / "missing-library"

        with TestClient(create_app(_settings(root, library))) as client:
            health = client.get("/api/v1/health").json()
            errors = client.get("/api/v1/admin/scan-errors").json()["errors"]

        assert health["songCount"] == 0
        assert health["errorCount"] == 1
        assert health["lastScanStatus"] == "completed_with_errors"
        assert errors[0]["errorCode"] == "library_unavailable"
        assert errors[0]["relativePath"] == "."
        assert library.exists() is False


def test_openapi_matches_shared_v1_snapshot() -> None:
    snapshot_path = Path(__file__).parents[3] / "shared" / "api" / "openapi-v1.json"
    expected = json.loads(snapshot_path.read_text(encoding="utf-8"))
    assert create_app().openapi() == expected
