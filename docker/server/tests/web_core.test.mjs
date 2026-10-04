import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  classifyDelta,
  createTimingMap,
  eventTimestampToSongTime,
  normalizeCalibrationMs,
  parseChartNotes,
  parseNotes,
} from "../src/foxdroid_server/web/core.mjs";
import { evaluateInputSequence } from "../src/foxdroid_server/web/gameplay.mjs";

const noteVectors = JSON.parse(await readFile(
  new URL("../../../shared/test-vectors/chart-notes.json", import.meta.url),
  "utf8",
));
const judgmentVectors = JSON.parse(await readFile(
  new URL("../../../shared/test-vectors/judgment-sequences.json", import.meta.url),
  "utf8",
));

test("converts BPM changes to a monotonic timeline", () => {
  const timeline = createTimingMap({ BPMS: "0=120,4=240" });
  assert.equal(timeline.beatToSeconds(4), 2);
  assert.equal(timeline.beatToSeconds(8), 3);
});

test("applies stops and delays before notes on the same beat", () => {
  const timeline = createTimingMap({
    BPMS: "0=120",
    STOPS: "2=1",
    DELAYS: "4=0.5",
  });
  assert.equal(timeline.beatToSeconds(2), 2);
  assert.equal(timeline.beatToSeconds(4), 3.5);
});

test("warps consume no time and BPM changes inside them still take effect", () => {
  const timeline = createTimingMap({ BPMS: "0=120,3=60", WARPS: "2=2" });
  assert.equal(timeline.beatToSeconds(2), 1);
  assert.equal(timeline.beatToSeconds(3), 1);
  assert.equal(timeline.beatToSeconds(4), 1);
  assert.equal(timeline.beatToSeconds(5), 2);
  assert.equal(timeline.isWarpedBeat(2), true);
  assert.equal(timeline.isWarpedBeat(3), true);
  assert.equal(timeline.isWarpedBeat(4), false);
});

test("a pause makes its exact beat playable inside a warp", () => {
  const timeline = createTimingMap({
    BPMS: "0=120",
    WARPS: "2=2",
    STOPS: "3=0.5",
  });
  assert.equal(timeline.isWarpedBeat(3), false);
  assert.equal(timeline.beatToSeconds(3), 1.5);
});

test("removes unplayable tap notes inside a warp", () => {
  const notes = parseNotes({
    timing: { BPMS: "0=120", WARPS: "1=2" },
    noteData: "1000\n0100\n0010\n0001",
  });
  assert.deepEqual(notes.map(({ lane, beat }) => ({ lane, beat })), [
    { lane: 0, beat: 0 },
    { lane: 3, beat: 3 },
  ]);
});

test("converts four-panel tap rows and offset to note times", () => {
  const notes = parseNotes({
    timing: { BPMS: "0=120", OFFSET: "-0.125" },
    noteData: "1000\n0000\n0100\n0000,0010\n0000\n0001\n0000",
  });
  assert.deepEqual(notes.map(({ lane, beat, time }) => ({ lane, beat, time })), [
    { lane: 0, beat: 0, time: .125 },
    { lane: 1, beat: 2, time: 1.125 },
    { lane: 2, beat: 4, time: 2.125 },
    { lane: 3, beat: 6, time: 3.125 },
  ]);
});

test("uses deterministic judgment boundaries", () => {
  assert.equal(classifyDelta(.045).name, "Perfect");
  assert.equal(classifyDelta(-.09).name, "Great");
  assert.equal(classifyDelta(.135).name, "Good");
  assert.equal(classifyDelta(.18).name, "Miss");
  assert.equal(classifyDelta(.181), null);
});

test("removes main-thread queue delay from keyboard input time", () => {
  assert.equal(eventTimestampToSongTime({
    eventTimestampMs: 5000,
    performanceNowMs: 5080,
    performanceTimeOriginMs: 1_700_000_000_000,
    audioContextTimeSeconds: 15.08,
    songStartAudioTimeSeconds: 10,
  }), 5);
});

test("normalizes epoch-based event timestamps", () => {
  assert.equal(eventTimestampToSongTime({
    eventTimestampMs: 1_700_000_005_000,
    performanceNowMs: 5080,
    performanceTimeOriginMs: 1_700_000_000_000,
    audioContextTimeSeconds: 15.08,
    songStartAudioTimeSeconds: 10,
  }), 5);
});

test("rejects an unusable event timestamp", () => {
  assert.equal(eventTimestampToSongTime({
    eventTimestampMs: Number.NaN,
    performanceNowMs: 5080,
    performanceTimeOriginMs: 1_700_000_000_000,
    audioContextTimeSeconds: 15.08,
    songStartAudioTimeSeconds: 10,
  }), null);
});

test("normalizes persisted calibration values", () => {
  assert.equal(normalizeCalibrationMs("42.4"), 42);
  assert.equal(normalizeCalibrationMs(-999), -250);
  assert.equal(normalizeCalibrationMs(999), 250);
  assert.equal(normalizeCalibrationMs("invalid"), 0);
});

for (const vector of noteVectors.cases) {
  test(`shared chart vector: ${vector.name}`, () => {
    assert.deepEqual(parseChartNotes(vector.chart), vector.expected);
  });
}

test("omits malformed unclosed durations from the logical model", () => {
  assert.deepEqual(parseChartNotes({
    timing: { BPMS: "0=120" },
    noteData: "2000\n0000\n0000\n0000",
  }), []);
});

for (const vector of judgmentVectors.cases) {
  test(`shared judgment vector: ${vector.name}`, () => {
    assert.deepEqual(
      evaluateInputSequence(vector.notes, vector.inputs, judgmentVectors.rules),
      vector.expected,
    );
  });
}

test("mine rules can require a fresh step", () => {
  const result = evaluateInputSequence(
    [{ lane: 0, type: "mine", time: 1 }],
    [
      { lane: 0, action: "down", time: 0.5 },
      { lane: 0, action: "up", time: 1.2 },
    ],
    { requireStepOnMines: true },
  );
  assert.equal(result[0].judgment, "AvoidMine");
});
