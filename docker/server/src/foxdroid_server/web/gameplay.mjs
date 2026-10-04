import { classifyDelta } from "./core.mjs";

export const defaultGameplayRules = Object.freeze({
  tapWindowSeconds: 0.18,
  holdGraceSeconds: 0.25,
  rollGraceSeconds: 0.5,
  mineWindowSeconds: 0.09,
  requireStepOnMines: false,
});

function normalizedEvents(inputs) {
  return inputs
    .filter((event) => Number.isFinite(event.time)
      && Number.isInteger(event.lane)
      && ["down", "up"].includes(event.action))
    .map((event, index) => ({ ...event, index }))
    .sort((a, b) => a.time - b.time || a.index - b.index);
}

function laneIsHeldAt(events, lane, time) {
  let held = false;
  for (const event of events) {
    if (event.time > time) break;
    if (event.lane === lane) held = event.action === "down";
  }
  return held;
}

function findHeadInput(note, downs, used, windowSeconds) {
  const candidates = downs
    .filter((event) => event.lane === note.lane
      && !used.has(event.index)
      && Math.abs(event.time - note.time) <= windowSeconds)
    .sort((a, b) => Math.abs(a.time - note.time) - Math.abs(b.time - note.time)
      || a.time - b.time);
  const input = candidates[0];
  if (!input) return null;
  used.add(input.index);
  return input;
}

function holdSurvives(note, headInput, events, graceSeconds) {
  let held = true;
  let releasedAt = null;
  for (const event of events) {
    if (event.lane !== note.lane || event.time <= headInput.time || event.time > note.endTime) {
      continue;
    }
    if (event.action === "up" && held) {
      held = false;
      releasedAt = event.time;
    } else if (event.action === "down" && !held) {
      if (event.time - releasedAt > graceSeconds) return false;
      held = true;
      releasedAt = null;
    }
  }
  return held || note.endTime - releasedAt <= graceSeconds;
}

function rollSurvives(note, headInput, downs, graceSeconds) {
  const taps = downs.filter((event) => event.lane === note.lane
    && event.time > headInput.time
    && event.time <= note.endTime);
  let lastTap = headInput.time;
  for (const tap of taps) {
    if (tap.time - lastTap > graceSeconds) return false;
    lastTap = tap.time;
  }
  return note.endTime - lastTap <= graceSeconds;
}

export function evaluateSustainBody(note, headInputTime, inputs, ruleOverrides = {}) {
  const rules = { ...defaultGameplayRules, ...ruleOverrides };
  const events = normalizedEvents(inputs);
  const headInput = { lane: note.lane, time: headInputTime };
  const survived = note.type === "roll"
    ? rollSurvives(
      note,
      headInput,
      events.filter((event) => event.action === "down"),
      rules.rollGraceSeconds,
    )
    : holdSurvives(note, headInput, events, rules.holdGraceSeconds);
  return survived ? "Held" : "LetGo";
}

export function evaluateInputSequence(notes, inputs, ruleOverrides = {}) {
  const rules = { ...defaultGameplayRules, ...ruleOverrides };
  const events = normalizedEvents(inputs);
  const downs = events.filter((event) => event.action === "down");
  const usedDowns = new Set();

  return [...notes]
    .sort((a, b) => a.time - b.time || a.lane - b.lane)
    .map((note) => {
      if (note.type === "mine") {
        const stepped = downs.some((event) => event.lane === note.lane
          && Math.abs(event.time - note.time) <= rules.mineWindowSeconds);
        const held = !rules.requireStepOnMines && laneIsHeldAt(events, note.lane, note.time);
        return { lane: note.lane, type: note.type, judgment: stepped || held ? "HitMine" : "AvoidMine" };
      }

      const headInput = findHeadInput(note, downs, usedDowns, rules.tapWindowSeconds);
      const headJudgment = headInput ? classifyDelta(headInput.time - note.time)?.name : "Miss";
      const successfulHead = headInput && headJudgment && headJudgment !== "Miss";
      if (note.type === "tap") {
        return { lane: note.lane, type: note.type, judgment: headJudgment || "Miss" };
      }
      if (!successfulHead) {
        return { lane: note.lane, type: note.type, headJudgment: headJudgment || "Miss", bodyJudgment: "Missed" };
      }

      return {
        lane: note.lane,
        type: note.type,
        headJudgment,
        bodyJudgment: evaluateSustainBody(note, headInput.time, events, rules),
      };
    });
}
