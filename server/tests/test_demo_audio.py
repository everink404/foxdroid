from __future__ import annotations

import subprocess
import sys
import wave
from pathlib import Path


def test_demo_audio_is_deterministic_pcm(tmp_path: Path) -> None:
    generator = Path(__file__).parents[1] / "tools" / "generate_demo_audio.py"
    first = tmp_path / "first.wav"
    second = tmp_path / "second.wav"

    subprocess.run([sys.executable, str(generator), str(first)], check=True)
    subprocess.run([sys.executable, str(generator), str(second)], check=True)

    assert first.read_bytes() == second.read_bytes()
    with wave.open(str(first), "rb") as track:
        assert track.getnchannels() == 1
        assert track.getsampwidth() == 2
        assert track.getframerate() == 48_000
        assert track.getnframes() == 240_000
