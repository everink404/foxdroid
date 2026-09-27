const EPSILON = 1e-7;

function parsePairs(value, isValidValue) {
  const result = [];
  for (const entry of String(value || "").split(",")) {
    const [beat, amount] = entry.split("=").map(Number);
    if (Number.isFinite(beat) && isValidValue(amount)) result.push({ beat, amount });
  }
  return result.sort((a, b) => a.beat - b.beat);
}

export function parseBpms(value) {
  const result = parsePairs(value || "0=120", (bpm) => bpm > 0)
    .map(({ beat, amount }) => ({ beat, bpm: amount }));
  return result.length ? result : [{ beat: 0, bpm: 120 }];
}

function lastValueByBeat(segments, valueKey) {
  const values = new Map();
  for (const segment of segments) values.set(segment.beat, segment[valueKey]);
  return values;
}

export function createTimingMap(timing = {}) {
  const bpms = parseBpms(timing.BPMS);
  const stops = parsePairs(timing.STOPS, (seconds) => seconds > 0);
  const delays = parsePairs(timing.DELAYS, (seconds) => seconds > 0);
  const warps = parsePairs(timing.WARPS, (beats) => beats > 0);
  const bpmAt = lastValueByBeat(bpms, "bpm");
  const stopAt = lastValueByBeat(stops, "amount");
  const delayAt = lastValueByBeat(delays, "amount");
  const warpAt = lastValueByBeat(warps, "amount");
  const eventBeats = [...new Set([...bpmAt.keys(), ...stopAt.keys(), ...delayAt.keys(), ...warpAt.keys()])]
    .filter((beat) => beat >= 0)
    .sort((a, b) => a - b);

  function beatToSeconds(targetBeat) {
    let time = 0;
    let lastBeat = 0;
    let bpm = 120;
    let warpUntil = Number.NEGATIVE_INFINITY;

    for (const change of bpms) {
      if (change.beat <= 0) bpm = change.bpm;
      else break;
    }

    for (const eventBeat of eventBeats) {
      if (eventBeat > targetBeat + EPSILON) break;
      const movingStart = Math.max(lastBeat, Math.min(eventBeat, warpUntil));
      time += Math.max(0, eventBeat - movingStart) * 60 / bpm;
      lastBeat = eventBeat;

      if (bpmAt.has(eventBeat)) bpm = bpmAt.get(eventBeat);
      if (delayAt.has(eventBeat)) time += delayAt.get(eventBeat);
      if (stopAt.has(eventBeat)) time += stopAt.get(eventBeat);
      if (warpAt.has(eventBeat)) warpUntil = Math.max(warpUntil, eventBeat + warpAt.get(eventBeat));
    }

    const movingStart = Math.max(lastBeat, Math.min(targetBeat, warpUntil));
    return time + Math.max(0, targetBeat - movingStart) * 60 / bpm;
  }

  function isWarpedBeat(beat) {
    const hasPause = [...stopAt.keys(), ...delayAt.keys()]
      .some((pauseBeat) => Math.abs(pauseBeat - beat) <= EPSILON);
    if (hasPause) return false;
    return warps.some(({ beat: start, amount: length }) =>
      start <= beat + EPSILON && beat < start + length - EPSILON);
  }

  return { beatToSeconds, isWarpedBeat };
}

export function parseChartNotes(chart) {
  const timeline = createTimingMap(chart.timing);
  const offset = Number(chart.timing.OFFSET || 0);
  const notes = [];
  const activeDurations = new Map();
  const measures = chart.noteData.split(",");
  measures.forEach((measure, measureIndex) => {
    const rows = measure.split(/\r?\n/).map((row) => row.trim())
      .filter((row) => /^[0-9A-Za-z]+$/.test(row));
    rows.forEach((row, rowIndex) => {
      const beat = measureIndex * 4 + rowIndex * 4 / rows.length;
      const warped = timeline.isWarpedBeat(beat);
      [...row.slice(0, 4)].forEach((symbol, lane) => {
        const time = timeline.beatToSeconds(beat) - offset;
        if (symbol === "3") {
          const head = activeDurations.get(lane);
          activeDurations.delete(lane);
          if (head && beat > head.beat && time > head.time) {
            notes.push({ ...head, endBeat: beat, endTime: time });
          }
        } else if (!warped && symbol === "1") {
          notes.push({ lane, beat, time, type: "tap" });
        } else if (!warped && symbol.toUpperCase() === "M") {
          notes.push({ lane, beat, time, type: "mine" });
        } else if (!warped && (symbol === "2" || symbol === "4") && !activeDurations.has(lane)) {
          activeDurations.set(lane, {
            lane,
            beat,
            time,
            type: symbol === "2" ? "hold" : "roll",
          });
        }
      });
    });
  });
  return notes.sort((a, b) => a.time - b.time || a.lane - b.lane);
}

export function parseNotes(chart) {
  return parseChartNotes(chart)
    .filter((note) => note.type === "tap")
    .map((note) => ({ ...note, judged: false }));
}

export function classifyDelta(deltaSeconds) {
  const delta = Math.abs(deltaSeconds);
  if (delta <= .045) return { name: "Perfect", points: 1000, accuracy: 1 };
  if (delta <= .09) return { name: "Great", points: 700, accuracy: .8 };
  if (delta <= .135) return { name: "Good", points: 300, accuracy: .5 };
  if (delta <= .18) return { name: "Miss", points: 0, accuracy: 0 };
  return null;
}

export function eventTimestampToSongTime({
  eventTimestampMs,
  performanceNowMs,
  performanceTimeOriginMs,
  audioContextTimeSeconds,
  songStartAudioTimeSeconds,
}) {
  const inputs = [
    eventTimestampMs,
    performanceNowMs,
    audioContextTimeSeconds,
    songStartAudioTimeSeconds,
  ];
  if (!inputs.every(Number.isFinite)) return null;

  let relativeEventTimestampMs = eventTimestampMs;
  if (eventTimestampMs > 1e12) {
    if (!Number.isFinite(performanceTimeOriginMs)) return null;
    relativeEventTimestampMs -= performanceTimeOriginMs;
  }

  const queuedForSeconds = (performanceNowMs - relativeEventTimestampMs) / 1000;
  const audioTimeAtEvent = audioContextTimeSeconds - queuedForSeconds;
  return audioTimeAtEvent - songStartAudioTimeSeconds;
}

export function normalizeCalibrationMs(value) {
  const milliseconds = Number(value);
  if (!Number.isFinite(milliseconds)) return 0;
  return Math.max(-250, Math.min(250, Math.round(milliseconds)));
}
