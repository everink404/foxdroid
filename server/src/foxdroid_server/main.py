from __future__ import annotations

import uvicorn

from .config import Settings


def run() -> None:
    settings = Settings.from_env()
    uvicorn.run(
        "foxdroid_server.api:app",
        host=settings.host,
        port=settings.port,
        log_level="info",
    )


if __name__ == "__main__":
    run()
