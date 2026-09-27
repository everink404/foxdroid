"""Export the checked-in FoxDroid API v1 OpenAPI contract."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from foxdroid_server.api import create_app


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Destination OpenAPI JSON file")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(create_app().openapi(), indent=2, sort_keys=True, ensure_ascii=False)
    args.output.write_text(f"{payload}\n", encoding="utf-8")


if __name__ == "__main__":
    main()
