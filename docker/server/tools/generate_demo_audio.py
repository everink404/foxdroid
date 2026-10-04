"""Generate the deterministic, copyright-safe FoxDroid demo click track."""

from __future__ import annotations

import argparse
import math
import struct
import wave
from pathlib import Path

SAMPLE_RATE = 48_000
DURATION_SECONDS = 5.0
BEAT_SECONDS = 0.5
PULSE_SECONDS = 0.045


def sample_at(time_seconds: float) -> float:
    beat_index = int(time_seconds / BEAT_SECONDS)
    within_beat = time_seconds - beat_index * BEAT_SECONDS
    if within_beat >= PULSE_SECONDS:
        return 0.0
    frequency = 1_320 if beat_index % 4 == 0 else 880
    envelope = math.exp(-within_beat * 75)
    return 0.28 * envelope * math.sin(2 * math.pi * frequency * within_beat)


def generate(output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    frame_count = round(SAMPLE_RATE * DURATION_SECONDS)
    frames = bytearray()
    for frame in range(frame_count):
        value = round(sample_at(frame / SAMPLE_RATE) * 32_767)
        frames.extend(struct.pack("<h", value))
    with wave.open(str(output), "wb") as target:
        target.setnchannels(1)
        target.setsampwidth(2)
        target.setframerate(SAMPLE_RATE)
        target.writeframes(frames)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Destination .wav file")
    args = parser.parse_args()
    generate(args.output)


if __name__ == "__main__":
    main()
