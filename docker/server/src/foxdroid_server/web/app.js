import {
  classifyDelta,
  eventTimestampToSongTime,
  normalizeCalibrationMs,
  parseChartNotes,
} from "./core.mjs";
import { defaultGameplayRules, evaluateSustainBody } from "./gameplay.mjs";

const $ = (selector) => document.querySelector(selector);
const views = ["library", "song", "game", "result"];
const laneKeys = new Map([["KeyD", 0], ["KeyF", 1], ["KeyJ", 2], ["KeyK", 3]]);
const laneColors = ["#ff5577", "#48c8ff", "#8b6cff", "#ffd250"];
const calibrationStorageKey = "foxdroid.web.calibration.v1";
const state = {
  songs: [], song: null, chart: null, notes: [], audioBuffer: null, audioContext: null,
  source: null, startAt: 0, animation: 0, running: false, score: 0, combo: 0,
  judged: 0, accuracyPoints: 0, audioMonitorAttached: false,
  inputOffsetMs: 0, visualOffsetMs: 0,
  inputEvents: [], heldLanes: new Set(),
  counts: { Perfect: 0, Great: 0, Good: 0, Miss: 0, Held: 0, LetGo: 0, HitMine: 0, AvoidMine: 0 },
};

function loadCalibration() {
  try {
    const saved = JSON.parse(localStorage.getItem(calibrationStorageKey) || "{}");
    state.inputOffsetMs = normalizeCalibrationMs(saved.inputOffsetMs);
    state.visualOffsetMs = normalizeCalibrationMs(saved.visualOffsetMs);
  } catch {
    state.inputOffsetMs = 0;
    state.visualOffsetMs = 0;
  }
}

function updateCalibrationControls() {
  $("#input-offset").value = state.inputOffsetMs;
  $("#visual-offset").value = state.visualOffsetMs;
  $("#input-offset-value").textContent = `${state.inputOffsetMs} ms`;
  $("#visual-offset-value").textContent = `${state.visualOffsetMs} ms`;
}

function changeCalibration(kind, value) {
  state[kind] = normalizeCalibrationMs(value);
  try {
    localStorage.setItem(calibrationStorageKey, JSON.stringify({
      inputOffsetMs: state.inputOffsetMs,
      visualOffsetMs: state.visualOffsetMs,
    }));
  } catch {}
  updateCalibrationControls();
}

function showView(name) {
  for (const view of views) $(`#${view}-view`).hidden = view !== name;
}

async function json(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) throw new Error(`请求失败 (${response.status})`);
  return response.json();
}

function renderSongs(query = "") {
  const needle = query.trim().toLocaleLowerCase();
  const matching = state.songs.filter((song) =>
    `${song.title} ${song.artist} ${song.groupName}`.toLocaleLowerCase().includes(needle));
  $("#songs").replaceChildren(...matching.map((song) => {
    const item = document.createElement("li");
    item.className = "song-card";
    const button = document.createElement("button");
    button.type = "button";
    const group = document.createElement("small");
    group.textContent = song.groupName;
    const title = document.createElement("strong");
    title.textContent = song.title;
    const meta = document.createElement("small");
    meta.textContent = `${song.artist || "未知艺术家"} · ${song.chartCount} 个谱面`;
    button.append(group, title, meta);
    button.addEventListener("click", () => selectSong(song.id));
    item.append(button);
    return item;
  }));
  $("#library-message").hidden = matching.length !== 0;
  $("#library-message").textContent = state.songs.length ? "没有符合条件的歌曲。" : "曲库中还没有歌曲。";
}

async function loadCatalog(triggerScan = false) {
  const message = $("#library-message");
  message.hidden = false;
  message.classList.remove("error");
  message.textContent = triggerScan ? "正在重新扫描曲库…" : "正在读取曲库…";
  try {
    if (triggerScan) await json("/api/v1/admin/scan", { method: "POST" });
    const [server, catalog] = await Promise.all([json("/api/v1/server"), json("/api/v1/catalog")]);
    state.songs = catalog.songs;
    $("#server-status").textContent = `${server.name} · ${catalog.songs.length} 首 · 清单版本 ${catalog.catalogRevision}`;
    message.hidden = true;
    renderSongs($("#search").value);
  } catch (error) {
    message.classList.add("error");
    message.textContent = `无法读取曲库：${error.message}`;
  }
}

async function selectSong(songId) {
  try {
    state.song = await json(`/api/v1/songs/${encodeURIComponent(songId)}`);
    $("#song-group").textContent = state.song.groupName;
    $("#song-title").textContent = state.song.title;
    $("#song-artist").textContent = state.song.artist || "未知艺术家";
    $("#chart-select").replaceChildren(...state.song.charts.map((chart) => {
      const option = document.createElement("option");
      option.value = chart.id;
      option.textContent = `${chart.difficulty} · ${chart.meter ?? "?"} · ${chart.stepType}`;
      return option;
    }));
    $("#start").disabled = state.song.charts.length === 0;
    $("#prepare-status").classList.remove("error");
    $("#prepare-status").textContent = state.song.assets.some((asset) => asset.kind === "music")
      ? "音频和谱面将在开始前完整加载。" : "此测试歌曲没有音频，将使用静音时钟验证输入和判定。";
    showView("song");
  } catch (error) {
    $("#library-message").hidden = false;
    $("#library-message").classList.add("error");
    $("#library-message").textContent = `无法读取歌曲：${error.message}`;
  }
}

async function prepareGame() {
  const status = $("#prepare-status");
  $("#start").disabled = true;
  status.classList.remove("error");
  status.textContent = "正在把谱面和音频准备到浏览器…";
  try {
    state.chart = await json(`/api/v1/charts/${encodeURIComponent($("#chart-select").value)}`);
    state.notes = parseChartNotes(state.chart);
    if (!state.notes.length) throw new Error("所选谱面没有当前版本可识别的音符");
    state.audioContext ||= new AudioContext({ latencyHint: "interactive" });
    await state.audioContext.resume();
    if (!state.audioMonitorAttached) {
      state.audioContext.addEventListener("statechange", () => {
        if (state.running && state.audioContext.state !== "running") {
          abortGame("音频时钟已中断，本局未计入结果。请恢复页面后重新开始。");
        }
      });
      state.audioMonitorAttached = true;
    }
    state.audioBuffer = null;
    const music = state.song.assets.find((asset) => asset.kind === "music");
    if (music) {
      const response = await fetch(`/api/v1/assets/${encodeURIComponent(music.id)}`);
      if (!response.ok) throw new Error("音频加载失败");
      state.audioBuffer = await state.audioContext.decodeAudioData(await response.arrayBuffer());
    }
    startGame();
  } catch (error) {
    status.classList.add("error");
    status.textContent = `无法开始：${error.message}`;
    $("#start").disabled = false;
  }
}

function resetScore() {
  state.score = 0; state.combo = 0; state.judged = 0; state.accuracyPoints = 0;
  state.inputEvents = [];
  state.heldLanes.clear();
  state.counts = { Perfect: 0, Great: 0, Good: 0, Miss: 0, Held: 0, LetGo: 0, HitMine: 0, AvoidMine: 0 };
  state.notes.forEach((note) => {
    note.headJudged = false;
    note.headInputTime = null;
    note.bodyJudgment = null;
    note.complete = false;
  });
  updateScore();
}

function startGame() {
  resetScore();
  state.running = true;
  state.startAt = state.audioContext.currentTime + 1.2;
  if (state.audioBuffer) {
    state.source = state.audioContext.createBufferSource();
    state.source.buffer = state.audioBuffer;
    state.source.connect(state.audioContext.destination);
    state.source.start(state.startAt);
  }
  $("#playing-title").textContent = state.song.title;
  $("#judgment").textContent = "READY";
  showView("game");
  state.animation = requestAnimationFrame(frame);
}

function songTime() { return state.audioContext.currentTime - state.startAt; }

function judge(lane, inputTime) {
  if (!state.running || inputTime < -0.2) return;
  const candidate = state.notes.filter((note) => !note.headJudged
      && note.type !== "mine" && note.lane === lane)
    .sort((a, b) => Math.abs(a.time - inputTime) - Math.abs(b.time - inputTime))[0];
  if (!candidate) return;
  const delta = Math.abs(candidate.time - inputTime);
  const result = classifyDelta(delta);
  if (result) applyHeadJudgment(candidate, inputTime, result.name, result.points, result.accuracy);
}

function applyHeadJudgment(note, inputTime, name, points, accuracy) {
  note.headJudged = true;
  note.headInputTime = inputTime;
  if (note.type === "tap" || name === "Miss") note.complete = true;
  state.judged += 1;
  state.counts[name] += 1;
  state.accuracyPoints += accuracy;
  state.score += points;
  state.combo = name === "Miss" ? 0 : state.combo + 1;
  $("#judgment").textContent = name.toUpperCase();
  updateScore();
}

function applyBodyJudgment(note, name) {
  note.bodyJudgment = name;
  note.complete = true;
  state.counts[name] += 1;
  if (name === "Held") {
    state.score += 500;
    state.combo += 1;
  } else {
    state.combo = 0;
  }
  $("#judgment").textContent = name.toUpperCase();
  updateScore();
}

function hitMine(note) {
  if (note.complete) return;
  note.complete = true;
  state.counts.HitMine += 1;
  state.score = Math.max(0, state.score - 500);
  state.combo = 0;
  $("#judgment").textContent = "MINE";
  updateScore();
}

function updateScore() {
  $("#score").textContent = state.score.toLocaleString();
  $("#combo").textContent = state.combo;
  $("#accuracy").textContent = `${(state.judged ? state.accuracyPoints / state.judged * 100 : 100).toFixed(2)}%`;
}

function draw(now) {
  const canvas = $("#stage");
  const context = canvas.getContext("2d");
  const laneWidth = canvas.width / 4;
  const receptorY = 92;
  context.clearRect(0, 0, canvas.width, canvas.height);
  for (let lane = 0; lane < 4; lane += 1) {
    context.fillStyle = lane % 2 ? "#0e1420" : "#0a0f18";
    context.fillRect(lane * laneWidth, 0, laneWidth, canvas.height);
    context.strokeStyle = "#273047";
    context.strokeRect(lane * laneWidth, 0, laneWidth, canvas.height);
    context.strokeStyle = laneColors[lane];
    context.lineWidth = 4;
    context.strokeRect(lane * laneWidth + 12, receptorY - 15, laneWidth - 24, 30);
  }
  for (const note of state.notes) {
    if (note.complete) continue;
    const x = note.lane * laneWidth;
    const y = receptorY + (note.time - now) * 280;
    context.fillStyle = laneColors[note.lane];
    if (note.type === "hold" || note.type === "roll") {
      const endY = receptorY + (note.endTime - now) * 280;
      if (Math.max(y, endY) >= -30 && Math.min(y, endY) <= canvas.height + 30) {
        context.globalAlpha = note.type === "roll" ? .55 : .35;
        context.fillRect(x + laneWidth * .35, Math.min(y, endY), laneWidth * .3, Math.abs(endY - y));
        context.globalAlpha = 1;
        context.fillRect(x + 13, y - 12, laneWidth - 26, 24);
        context.fillRect(x + 20, endY - 6, laneWidth - 40, 12);
      }
    } else if (note.type === "mine") {
      if (y >= -30 && y <= canvas.height + 30) {
        context.beginPath();
        context.arc(x + laneWidth / 2, y, 15, 0, Math.PI * 2);
        context.fill();
        context.strokeStyle = "#090b10";
        context.lineWidth = 4;
        context.beginPath();
        context.moveTo(x + laneWidth / 2 - 7, y - 7);
        context.lineTo(x + laneWidth / 2 + 7, y + 7);
        context.moveTo(x + laneWidth / 2 + 7, y - 7);
        context.lineTo(x + laneWidth / 2 - 7, y + 7);
        context.stroke();
      }
    } else if (y >= -30 && y <= canvas.height + 30) {
      context.fillRect(x + 13, y - 12, laneWidth - 26, 24);
    }
  }
  if (now < 0) {
    context.fillStyle = "#fff";
    context.font = "900 52px system-ui";
    context.textAlign = "center";
    context.fillText(String(Math.ceil(-now)), canvas.width / 2, canvas.height / 2);
  }
}

function frame() {
  if (!state.running) return;
  const now = songTime();
  for (const note of state.notes) {
    if (note.complete) continue;
    if (note.type === "mine") {
      if (now >= note.time
        && now <= note.time + defaultGameplayRules.mineWindowSeconds
        && state.heldLanes.has(note.lane)) hitMine(note);
      else if (now > note.time + defaultGameplayRules.mineWindowSeconds) {
        note.complete = true;
        state.counts.AvoidMine += 1;
      }
      continue;
    }
    if (!note.headJudged && note.time < now - defaultGameplayRules.tapWindowSeconds) {
      applyHeadJudgment(note, null, "Miss", 0, 0);
      continue;
    }
    if (note.headJudged && !note.complete && now >= note.endTime) {
      applyBodyJudgment(
        note,
        evaluateSustainBody(note, note.headInputTime, state.inputEvents),
      );
    }
  }
  draw(now + state.visualOffsetMs / 1000);
  const lastTime = Math.max(...state.notes.map((note) => note.endTime ?? note.time));
  const audioEnded = state.audioBuffer && now > state.audioBuffer.duration + .5;
  if (state.notes.every((note) => note.complete) && (now > lastTime + .8 || audioEnded)) finishGame();
  else state.animation = requestAnimationFrame(frame);
}

function stopPlayback() {
  state.running = false;
  state.heldLanes.clear();
  cancelAnimationFrame(state.animation);
  if (state.source) { try { state.source.stop(); } catch {} }
  state.source = null;
}

function abortGame(message) {
  if (!state.running) return;
  stopPlayback();
  showView("song");
  $("#prepare-status").classList.add("error");
  $("#prepare-status").textContent = message;
  $("#start").disabled = false;
}

function finishGame() {
  stopPlayback();
  const accuracy = state.judged ? state.accuracyPoints / state.judged * 100 : 0;
  const entries = [["分数", state.score.toLocaleString()], ["准确率", `${accuracy.toFixed(2)}%`], ...Object.entries(state.counts)];
  $("#result-stats").replaceChildren(...entries.map(([label, value]) => {
    const item = document.createElement("div");
    const caption = document.createElement("span"); caption.className = "muted"; caption.textContent = label;
    const number = document.createElement("strong"); number.textContent = value;
    item.append(caption, number); return item;
  }));
  showView("result");
}

function eventSongTime(event) {
  const rawTime = eventTimestampToSongTime({
    eventTimestampMs: event.timeStamp,
    performanceNowMs: performance.now(),
    performanceTimeOriginMs: performance.timeOrigin,
    audioContextTimeSeconds: state.audioContext?.currentTime,
    songStartAudioTimeSeconds: state.startAt,
  });
  return rawTime === null ? null : rawTime - state.inputOffsetMs / 1000;
}

document.addEventListener("keydown", (event) => {
  if (event.repeat || !laneKeys.has(event.code)) return;
  event.preventDefault();
  const inputTime = eventSongTime(event);
  if (!state.running || inputTime === null) return;
  const lane = laneKeys.get(event.code);
  state.heldLanes.add(lane);
  state.inputEvents.push({ lane, action: "down", time: inputTime });
  for (const note of state.notes) {
    if (note.type === "mine" && note.lane === lane && !note.complete
      && Math.abs(note.time - inputTime) <= defaultGameplayRules.mineWindowSeconds) hitMine(note);
  }
  judge(lane, inputTime);
});
document.addEventListener("keyup", (event) => {
  if (!laneKeys.has(event.code)) return;
  const inputTime = eventSongTime(event);
  const lane = laneKeys.get(event.code);
  state.heldLanes.delete(lane);
  if (state.running && inputTime !== null) {
    state.inputEvents.push({ lane, action: "up", time: inputTime });
  }
});
document.addEventListener("visibilitychange", () => {
  if (document.hidden) abortGame("页面失去焦点，本局已安全中止。重新开始后不会沿用旧时钟。");
});
$("#refresh").addEventListener("click", () => loadCatalog(true));
$("#search").addEventListener("input", (event) => renderSongs(event.target.value));
$("#back").addEventListener("click", () => showView("library"));
$("#start").addEventListener("click", prepareGame);
$("#input-offset").addEventListener("input", (event) => changeCalibration("inputOffsetMs", event.target.value));
$("#visual-offset").addEventListener("input", (event) => changeCalibration("visualOffsetMs", event.target.value));
$("#reset-calibration").addEventListener("click", () => {
  state.inputOffsetMs = 0;
  state.visualOffsetMs = 0;
  try { localStorage.removeItem(calibrationStorageKey); } catch {}
  updateCalibrationControls();
});
$("#quit").addEventListener("click", () => { stopPlayback(); showView("song"); $("#start").disabled = false; });
$("#result-back").addEventListener("click", () => { showView("library"); $("#start").disabled = false; });
$("#audio-capability").textContent = window.AudioContext ? "Web Audio 可用" : "Web Audio 不可用";
loadCalibration();
updateCalibrationControls();
loadCatalog();
