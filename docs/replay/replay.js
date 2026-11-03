"use strict";
/*
 * Replay of a recorded RPSO run on its model: a port of the editor's token replay
 * (btsn.workflowEditor TokenAnimator.java for the timeline, Canvas.java for drawing).
 * The events come from the analysis file's MARKING EVOLUTION section; nothing here simulates the net.
 */

// ---------------- Timing (AnimationConstants.java) ----------------
const VELOCITY = 0.5;                 // px per ms of replay
const MIN_TRAVEL_DURATION = 50;
const TIME_AT_EVENT_GEN = 300;
const FALLBACK_EG_TO_TIN = 150, FALLBACK_TO_PLACE = 120, TRAVEL_TO_TOUT = 80, FALLBACK_TO_NEXT = 300;
const MIN_VISIBLE_STEP = 250;         // shortest shown passage step (TokenAnimator.stretchShortSteps)

const PH = { AT_EG: "AT_EVENT_GENERATOR", TO_TIN: "TRAVELING_TO_TIN", BUFFERED: "BUFFERED_AT_TIN",
  TO_PLACE: "TRAVELING_TO_PLACE", AT_PLACE: "AT_PLACE", TO_TOUT: "TRAVELING_TO_TOUT", TO_NEXT: "TRAVELING_TO_NEXT_TIN",
  TO_TERM: "TRAVELING_TO_TERMINATE", AT_TERM: "AT_TERMINATE", EXPIRED: "EXPIRED" };

const VERSION_COLORS = { v001: "#e74c3c", v002: "#3498db", v003: "#2ecc71", v004: "#9b59b6",
  v005: "#f39c12", v006: "#1abc9c", v007: "#f1c40f", v008: "#34495e" };
const versionOf = id => { const n = Math.floor(Number(id) / 1000000); return isFinite(n) ? "v" + String(n).padStart(3, "0") : "v001"; };

// ---------------- Model and topology (TokenAnimator.buildTopologyFromCanvas) ----------------
function buildTopology(model, instanceNodes) {
  const els = model.elements || [], byId = new Map();
  for (const e of els) byId.set(e.id, e);
  const isTIn = e => e && e.type === "TRANSITION" && (e.transition_type === "T_in" || (!e.transition_type && /^T_in/.test(e.label || e.id)));
  const isTOut = e => e && e.type === "TRANSITION" && (e.transition_type === "T_out" || (!e.transition_type && /^T_out/.test(e.label || e.id)));
  const isTerm = e => e && (e.node_value === "TERMINATE_NODE" || e.node_type === "TerminateNode" || e.node_type === "SignalNode"
    || /terminate/i.test(e.label || ""));
  const T = { byId, places: [], placeToTIn: {}, placeToTOut: {}, tInToPlace: {}, tOutToTerminate: {}, tOutArcToTerminate: {}, alias: {}, arcs: new Map(),
    isTIn, isTOut, eventGens: [] };
  const serviceUse = {};
  for (const e of els) if (e.type === "PLACE" && e.service) serviceUse[e.service] = (serviceUse[e.service] || 0) + 1;
  for (const e of els) {
    const names = [e.id, e.label];
    if (e.type === "PLACE") {
      T.places.push(e);
      if (e.service && serviceUse[e.service] === 1) names.push(e.service);
      // Instrumentation names a place after its deployment node (P1_Place) and a transition T_in_<node>/T_out_<node>.
      const node = (instanceNodes && (instanceNodes[e.serviceInstance] || instanceNodes[e.service])) || "";
      if (node) { names.push(node + "_Place", node); e._node = node; }
    } else if (e.type === "EVENT_GENERATOR") T.eventGens.push(e);
    for (const n of names) if (n && !(n in T.alias)) T.alias[n] = e.id;
  }
  for (const a of model.arrows || []) {
    const s = byId.get(a.source), t = byId.get(a.target);
    if (!s || !t) continue;
    T.arcs.set(s.id + "->" + t.id, a);
    if (isTIn(s) && t.type === "PLACE") { T.placeToTIn[t.id] = s.id; T.tInToPlace[s.id] = t.id; }
    if (s.type === "PLACE" && isTOut(t)) T.placeToTOut[s.id] = t.id;
    if (isTOut(s) && isTerm(t)) {
      T.tOutToTerminate[s.id] = t.id;
      const d = (a.decision_value || "").trim();
      if (d) T.tOutArcToTerminate[s.id + "#" + d] = t.id;
    }
  }
  // The terminate node a token reached from a T_out: the one whose arc carries the recorded guard value
  // (TokenAnimator.terminateFor); else the T_out's terminate node; else the T_out itself.
  T.terminateFor = (tOut, arc) => (tOut && arc && T.tOutArcToTerminate[tOut + "#" + arc.trim()]) || T.tOutToTerminate[tOut] || tOut;
  for (const p of T.places) if (p._node) {
    const tin = T.placeToTIn[p.id], tout = T.placeToTOut[p.id];
    if (tin && !(("T_in_" + p._node) in T.alias)) T.alias["T_in_" + p._node] = tin;
    if (tout && !(("T_out_" + p._node) in T.alias)) T.alias["T_out_" + p._node] = tout;
  }
  // Without a deployment: P1_Place -> the place P1 (by id or label).
  T.resolve = name => {
    if (name == null || name === "") return name;
    if (name in T.alias) return T.alias[name];
    const m = /^(.*)_Place$/.exec(name);
    if (m && m[1] in T.alias) return T.alias[m[1]];
    return name;
  };
  T.center = id => { const e = byId.get(id); return e ? { x: e.x + Math.floor(e.width / 2), y: e.y + Math.floor(e.height / 2) } : null; };
  return T;
}

// ---------------- Parsing (TokenAnimator.parseEventsFromText) ----------------
function lastAnalyzerRun(text) {
  const re = /^[ \t]*(?:\[java\][ \t]*)?(?:Auto-detected|Analyzing ALL) \d+ workflow bases/gm;
  let last = -1, runs = 0, m;
  while ((m = re.exec(text))) { last = m.index; runs++; }
  return runs < 2 ? text : text.slice(last);
}
const RANK = { GENERATED: 0, BUFFERED: 1, ENTER: 2, JOIN_SURVIVOR: 2, JOIN_CONSUMED: 3, FORK: 4, EXIT: 5, TERMINATE: 6, FAILED: 7 };
function parseEvents(text, T) {
  const seen = new Set(), events = [];
  let order = 0;
  for (let line of lastAnalyzerRun(text).split(/\r?\n/)) {
    line = line.trim();
    if (line.startsWith("[java]")) line = line.slice(6).trim();
    if (!line.startsWith("Time=") || !line.includes("Marking=")) continue;
    let arc = null;
    const arcAt = line.indexOf(" Arc=");
    if (arcAt >= 0) { arc = line.slice(arcAt + 5).trim() || null; line = line.slice(0, arcAt); }
    const f = {};
    for (const part of line.split(" ")) { const i = part.indexOf("="); if (i > 0) f[part.slice(0, i)] = part.slice(i + 1); }
    const t = Number(f.Time), token = f.Token || "", marking = Number(f.Marking || 0);
    let type = f.EventType || (marking === 1 ? "ENTER" : "EXIT");
    if (!token || !isFinite(t) || marking < 0) continue;
    const ev = { t, token, version: versionOf(token), place: T.resolve(f.Place || ""), toPlace: T.resolve(f.ToPlace || null),
      transition: T.resolve(f.TransitionId || null), marking, type, arc, order: order++ };
    if (!ev.place && type !== "GENERATED") continue;
    const key = [t, token, ev.place, marking, type, ev.toPlace].join("|");
    if (seen.has(key)) continue;
    seen.add(key);
    events.push(ev);
  }
  // By time; at the same time exits before enters (MarkingEvent.compareTo), then in causal order.
  events.sort((a, b) => a.t - b.t || a.marking - b.marking || (RANK[a.type] ?? 9) - (RANK[b.type] ?? 9) || a.order - b.order);
  return events;
}
// The last moment every host was still recording (TokenAnimator.recordingHorizon).
function recordingHorizon(events) {
  const last = {};
  for (const e of events) if (e.place) last[e.place] = Math.max(last[e.place] ?? -Infinity, e.t);
  let h = Infinity;
  for (const e of events) {
    if (e.type !== "EXIT" || !e.toPlace) continue;
    const silent = last[e.toPlace];
    if (silent != null && e.t > silent) h = Math.min(h, silent);
  }
  return h;
}

// ---------------- Segments (TokenAnimator.generateSegments and its helpers) ----------------
function buildTimeline(events, T) {
  const dist = (a, b) => {
    const p = T.center(a), q = T.center(b);
    if (!p || !q) return -1;
    const arc = T.arcs.get(a + "->" + b), pts = [p, ...((arc && arc.waypoints) || []), q];
    let d = 0;
    for (let i = 1; i < pts.length; i++) d += Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y);
    return d;
  };
  const travel = (a, b, fallback) => { const d = dist(a, b); return d < 0 ? fallback : Math.max(Math.floor(d / VELOCITY), MIN_TRAVEL_DURATION); };

  let cutoff = 0, lastTime = events.length ? events[events.length - 1].t : 0;
  const horizon = recordingHorizon(events);
  if (horizon !== Infinity && horizon < lastTime) { events = events.filter(e => e.t <= horizon); cutoff = horizon; lastTime = horizon; }
  const END = lastTime + 10000;

  // A join fires when the last of its forks reaches the T_in: then the fork it keeps moves into the
  // place and the fork it consumes is gone. The recorded ENTER and JOIN_CONSUMED come a few ms later.
  const isJoin = p => { const t = T.byId.get(T.placeToTIn[p]); return !!t && (t.node_type === "JoinNode" || t.node_value === "JOIN_NODE"); };
  const fires = {}, lastArrival = {};
  for (const e of events) {
    if (e.type === "BUFFERED") lastArrival[e.place] = e.t;
    else if ((e.type === "ENTER" || e.type === "JOIN_SURVIVOR") && isJoin(e.place)) {
      const f = lastArrival[e.place];
      (fires[e.place] = fires[e.place] || []).push({ enter: e.t, fire: f != null && f <= e.t ? f : e.t });
    }
  }
  const fireFor = (p, t) => {
    let best = null;
    for (const x of fires[p] || []) if (!best || Math.abs(x.enter - t) < Math.abs(best.enter - t)) best = x;
    return best ? best.fire : null;
  };

  const forked = new Set();
  for (const e of events) if (e.type === "FORK") { const n = Number(e.token), k = n % 100; if (k >= 1 && k <= 99) forked.add(String(n - k)); }
  const byToken = new Map();
  for (const e of events) { if (!byToken.has(e.token)) byToken.set(e.token, []); byToken.get(e.token).push(e); }
  const segs = [];
  const seg = (tok, ver, phase, s, e, o = {}) => ({ tok, ver, phase, s, e, ...o });

  for (const [tok, evs] of byToken) {
    const ver = evs[0].version, path = [];
    const add = s => { if (s.e > s.s) path.push(s); return s; };
    const after = (i, pred) => { for (let j = i + 1; j < evs.length; j++) if (pred(evs[j])) return evs[j]; return null; };
    for (let i = 0; i < evs.length; i++) {
      const ev = evs[i];
      if (ev.type === "GENERATED") {
        // Appear at the generator, travel to the T_in; spare time is spent waiting there (generateRegularApproachSegments).
        const dest = ev.toPlace || ev.place, tIn = T.placeToTIn[dest], eg = ev.transition;
        const next = after(i, e => e.place === dest && (e.type === "BUFFERED" || e.type === "ENTER" || e.type === "JOIN_SURVIVOR"));
        if (!tIn || !next) continue;
        // A fork a generator lays straight into a place (an initial marking: a child token, numbered 1-99) shows at the
        // generator and travels to the T_in to arrive at its recorded time (generateApproachToRecordedArrival).
        const k = Number(tok) % 100;
        if (k >= 1 && k <= 99 && next.type === "BUFFERED" && T.byId.has(eg)) {
          const avail = Math.max(next.t - ev.t, 2 * MIN_TRAVEL_DURATION), from = next.t - avail;
          const atEg = Math.min(TIME_AT_EVENT_GEN, Math.floor(avail / 2)), toTIn = Math.min(travel(eg, tIn, FALLBACK_EG_TO_TIN), avail - atEg);
          add(seg(tok, ver, PH.AT_EG, from, next.t - toTIn, { eg, tIn }));
          add(seg(tok, ver, PH.TO_TIN, next.t - toTIn, next.t, { from: eg, to: tIn, tIn }));
          continue;
        }
        let g = ev.t, avail = next.t - g;
        if (avail < MIN_TRAVEL_DURATION * 4) { avail = MIN_TRAVEL_DURATION * 4; g = next.t - avail; }
        let atEg = TIME_AT_EVENT_GEN, toTIn = T.byId.has(eg) ? travel(eg, tIn, FALLBACK_EG_TO_TIN) : 0;
        const need = atEg + toTIn + MIN_TRAVEL_DURATION;
        if (avail <= need) { const k = avail / need; atEg = Math.max(MIN_TRAVEL_DURATION, Math.floor(atEg * k)); if (toTIn) toTIn = Math.max(MIN_TRAVEL_DURATION, Math.floor(toTIn * k)); }
        if (T.byId.has(eg)) {
          add(seg(tok, ver, PH.AT_EG, g, g + atEg, { eg, tIn }));
          add(seg(tok, ver, PH.TO_TIN, g + atEg, Math.min(g + atEg + toTIn, next.t), { from: eg, to: tIn, tIn }));
        }
        add(seg(tok, ver, PH.BUFFERED, Math.min(g + atEg + toTIn, next.t), next.t, { place: dest, tIn }));
      } else if (ev.type === "BUFFERED") {
        const tIn = T.placeToTIn[ev.place];
        const done = after(i, e => e.place === ev.place && (e.type === "ENTER" || e.type === "JOIN_CONSUMED" || e.type === "JOIN_SURVIVOR" || e.type === "FAILED"));
        const prev = path[path.length - 1];
        let end = done ? done.t : END;
        if (done && done.type === "JOIN_CONSUMED") { const f = fireFor(ev.place, done.t); if (f != null) end = Math.max(f, ev.t); }
        // Kept apart from the wait before it (a token held before its host recorded it), so the move into the place is
        // taken from this recorded wait, as in the editor, not from the whole time since the token was generated.
        add(seg(tok, ver, PH.BUFFERED, ev.t, end, { place: ev.place, tIn }));
      } else if (ev.type === "ENTER" || ev.type === "JOIN_SURVIVOR") {
        const p = ev.place, tIn = T.placeToTIn[p], tOut = T.placeToTOut[p];
        const exit = after(i, e => e.place === p && (e.type === "EXIT" || e.type === "TERMINATE" || e.type === "FAILED" || e.type === "JOIN_CONSUMED"));
        let atStart = ev.t, atEnd, toTout = null;
        if (exit && (exit.type === "EXIT" || exit.type === "TERMINATE")) {
          // A visit shorter than the usual move to T_out is split in half, so a fast service is still seen.
          atEnd = exit.t - TRAVEL_TO_TOUT;
          if (atEnd <= ev.t && exit.t > ev.t) atEnd = ev.t + Math.floor((exit.t - ev.t) / 2);
          if (tOut) toTout = seg(tok, ver, PH.TO_TOUT, Math.max(atEnd, ev.t), exit.t, { place: p, from: p, to: tOut, tOut });
        } else atEnd = exit ? Math.max(exit.t, ev.t + 1) : END;
        // Every visit moves the token from its T_in into the place (ensureTravelIntoPlaces): taken from the
        // step before it when that step ends here, otherwise from the first half of the visit.
        // At a join the move starts when the join fires, never before its last fork has arrived.
        const prev = path[path.length - 1], fire = isJoin(p) ? fireFor(p, ev.t) : null;
        if (tIn) {
          if (prev && prev.e === ev.t && prev.e - prev.s >= 2 && (fire == null || fire < ev.t)) {
            const start = fire != null ? Math.max(prev.s, fire) : prev.s + Math.floor((prev.e - prev.s) / 2);
            prev.e = start;
            add(seg(tok, ver, PH.TO_PLACE, start, ev.t, { place: p, from: tIn, to: p, tIn }));
          } else if (atEnd - atStart >= 2) {
            const mid = atStart + Math.floor((atEnd - atStart) / 2);
            add(seg(tok, ver, PH.TO_PLACE, atStart, mid, { place: p, from: tIn, to: p, tIn }));
            atStart = mid;
          }
        }
        add(seg(tok, ver, PH.AT_PLACE, atStart, atEnd, { place: p, tIn, tOut }));
        if (toTout) add(toTout);
      } else if (ev.type === "EXIT") {
        const tOut = T.placeToTOut[ev.place];
        if (!tOut) continue;
        const terminating = ev.toPlace === "TERMINATE" || /terminate/i.test(ev.toPlace || "");
        if (terminating && !T.byId.has(ev.toPlace)) {
          const term = T.terminateFor(tOut, ev.arc || (after(i, e => e.type === "TERMINATE") || {}).arc), d = term === tOut ? 0 : travel(tOut, term, FALLBACK_TO_NEXT);
          if (d) add(seg(tok, ver, PH.TO_TERM, ev.t, ev.t + d, { from: tOut, to: term, term }));
          add(seg(tok, ver, PH.AT_TERM, ev.t + d, END, { term }));
          continue;
        }
        if (forked.has(tok)) continue;
        const dest = ev.toPlace, tIn = T.placeToTIn[dest];
        if (!tIn) continue;
        const arrival = after(i, e => ["ENTER", "BUFFERED", "JOIN_SURVIVOR", "JOIN_CONSUMED", "TERMINATE", "FAILED"].includes(e.type));
        // A fork consumed at a join and sent on again by its place travels until it is recorded arriving
        // (the editor's rebirth after JOIN_CONSUMED); any other token at the pace its arc's length gives.
        const reborn = evs.slice(0, i).reverse().find(e => e.type === "JOIN_CONSUMED" || e.type === "EXIT" || e.type === "ENTER" || e.type === "JOIN_SURVIVOR");
        const recorded = reborn && reborn.type === "JOIN_CONSUMED"
          ? after(i, e => (e.type === "BUFFERED" || e.type === "ENTER") && e.place === dest) : null;
        const travelEnd = recorded ? recorded.t : ev.t + travel(tOut, tIn, FALLBACK_TO_NEXT);
        const reach = arrival ? Math.min(travelEnd, arrival.t) : travelEnd;
        add(seg(tok, ver, PH.TO_NEXT, ev.t, reach, { place: dest, from: tOut, to: tIn, tIn, tOut }));
        // At a join (or any T_in) the token waits for the rest of its time there (generateExitSegments).
        add(seg(tok, ver, PH.BUFFERED, reach, arrival ? arrival.t : END, { place: dest, tIn }));
      } else if (ev.type === "TERMINATE") {
        const tOut = T.placeToTOut[ev.place], term = tOut ? T.terminateFor(tOut, ev.arc) : null;
        const prev = path[path.length - 1];
        if (term && !(prev && (prev.phase === PH.AT_TERM || prev.phase === PH.TO_TERM))) {
          const d = term === tOut ? 0 : travel(tOut, term, FALLBACK_TO_NEXT);
          if (d) add(seg(tok, ver, PH.TO_TERM, ev.t, ev.t + d, { from: tOut, to: term, term }));
          add(seg(tok, ver, PH.AT_TERM, ev.t + d, END, { term }));
        }
      } else if (ev.type === "FAILED") {
        // The token's lifetime ran out (or its call failed): it is gone from here on. Show where it went out.
        let last = null;
        for (let k = path.length - 1; k >= 0; k--) {
          const s = path[k];
          if (s.s >= ev.t) { path.splice(k, 1); continue; }
          if (s.e > ev.t) s.e = ev.t;
          if (!last) last = s;
        }
        // A fork that expired on arrival is shown at the T_in of the host that recorded it.
        if (last && last.phase === PH.TO_NEXT && last.place === ev.place) last = { ...last, phase: PH.BUFFERED };
        if (last) add({ ...last, phase: PH.EXPIRED, s: ev.t, e: ev.t + 600, frozen: progressOf(last, last.e), was: last.phase });
      }
      // FORK and JOIN_CONSUMED draw nothing themselves: a consumed fork is out of sight until it is sent on again.
    }
    segs.push(...path);
  }
  segs.sort((a, b) => a.s - b.s);

  // Stretch steps too short to see (stretchShortSteps): the whole timeline together, only where needed.
  const times = Array.from(new Set(segs.flatMap(s => [s.s, s.e]))).sort((a, b) => a - b);
  const index = new Map(times.map((t, i) => [t, i]));
  const startsByEnd = new Map();
  for (const s of segs) {
    const passage = s.phase === PH.TO_PLACE || s.phase === PH.AT_PLACE || s.phase === PH.TO_TOUT || s.phase === PH.TO_TERM;
    if (!passage || s.e <= s.s) continue;
    const k = index.get(s.e);
    if (!startsByEnd.has(k)) startsByEnd.set(k, []);
    startsByEnd.get(k).push(index.get(s.s));
  }
  const shown = new Array(times.length);
  shown[0] = times[0];
  for (let i = 1; i < times.length; i++) {
    shown[i] = shown[i - 1] + (times[i] - times[i - 1]);
    for (const from of startsByEnd.get(i) || []) shown[i] = Math.max(shown[i], shown[from] + MIN_VISIBLE_STEP);
  }
  for (const s of segs) { s.rs = s.s; s.re = s.e; s.s = shown[index.get(s.s)]; s.e = shown[index.get(s.e)]; }
  const warp = (from, to) => x => {
    const n = from.length;
    if (!n) return x;
    if (x <= from[0]) return to[0] - (from[0] - x);
    if (x >= from[n - 1]) return to[n - 1] + (x - from[n - 1]);
    let lo = 0, hi = n - 1;
    while (hi - lo > 1) { const m = (lo + hi) >> 1; if (from[m] <= x) lo = m; else hi = m; }
    if (from[lo] === x) return to[lo];
    return to[lo] + Math.round((x - from[lo]) / (from[hi] - from[lo]) * (to[hi] - to[lo]));
  };
  const shownAt = warp(times, shown), realAt = warp(shown, times);

  // Replay window (generateSegments): a little before the first token, a little after the last step.
  const finite = segs.filter(s => s.re < END);
  const first = segs.length ? segs[0].s : 0;
  let end = finite.length ? Math.max(...finite.map(s => s.e)) : first;
  end += TRAVEL_TO_TOUT + FALLBACK_TO_NEXT + 500;
  if (cutoff) end = Math.min(end, shownAt(cutoff) + 500);
  const start = first - (TIME_AT_EVENT_GEN + FALLBACK_EG_TO_TIN + 500);
  return { segs, start, end, shownAt, realAt, cutoff, events };
}
function progressOf(s, t) { return s.e <= s.s ? 1 : Math.max(0, Math.min(1, (t - s.s) / (s.e - s.s))); }

// ---------------- Drawing (Canvas.java, ProcessElement.java, Arrow.java) ----------------
const SVGNS = "http://www.w3.org/2000/svg";
function el(name, attrs = {}, parent) {
  const n = document.createElementNS(SVGNS, name);
  for (const k in attrs) if (attrs[k] != null) n.setAttribute(k, attrs[k]);
  if (parent) parent.appendChild(n);
  return n;
}
function text(parent, x, y, str, o = {}) {
  const t = el("text", { x, y, "font-size": o.size || 12, "font-weight": o.bold ? 700 : 400, "font-style": o.italic ? "italic" : "normal",
    "text-anchor": o.anchor || "start", fill: o.fill || "#000", "font-family": "Helvetica, Arial, sans-serif" }, parent);
  if (o.baseline) t.setAttribute("dominant-baseline", o.baseline);
  t.textContent = str;
  return t;
}
function edgePoint(e, c, other) {
  const ang = Math.atan2(other.y - c.y, other.x - c.x);
  if (e.type === "PLACE") return { x: c.x + Math.trunc(e.width / 2 * Math.cos(ang)), y: c.y + Math.trunc(e.width / 2 * Math.sin(ang)) };
  const rot = (e.rotation || 0) * Math.PI / 180, la = ang - rot, dx = Math.cos(la), dy = Math.sin(la);
  const hw = Math.floor(e.width / 2), hh = Math.floor(e.height / 2);
  const t = Math.abs(dx) < 0.001 ? hh / Math.abs(dy) : Math.abs(dy) < 0.001 ? hw / Math.abs(dx) : Math.min(hw / Math.abs(dx), hh / Math.abs(dy));
  const lx = t * dx, ly = t * dy;
  return { x: c.x + Math.trunc(lx * Math.cos(rot) - ly * Math.sin(rot)), y: c.y + Math.trunc(lx * Math.sin(rot) + ly * Math.cos(rot)) };
}
const argbToCss = c => (typeof c === "number") ? "#" + ((c >>> 0) & 0xffffff).toString(16).padStart(6, "0") : "#000";
const NODE_LETTER = { EdgeNode: "E", XorNode: "X", JoinNode: "J", MergeNode: "M", XorMergeNode: "XM", ForkNode: "F", GatewayNode: "G", TerminateNode: "T", SignalNode: "S" };

function drawModel(svg, model, T) {
  svg.textContent = "";
  const els = model.elements || [], texts = model.textElements || [];
  // Bounds of everything drawn, with room for labels and badges.
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  const grow = (x, y) => { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); };
  for (const e of els) { grow(e.x - 30, e.y - 30); grow(e.x + e.width + 30, e.y + e.height + 30); }
  // Arcs that run outside the elements (a loop back drawn below them) and their labels.
  for (const a of model.arrows || []) for (const w of a.waypoints || []) {
    grow(w.x - 10, w.y - 10); grow(w.x + 10, w.y + 10);
    if (a.label) { const lx = w.x + (a.labelOffsetX || 0), ly = w.y + (a.labelOffsetY || 0); grow(lx - a.label.length * 3 - 10, ly - 12); grow(lx + a.label.length * 3 + 10, ly + 12); }
  }
  for (const t of texts) {
    const lines = (t.text || "").split("|"), longest = Math.max(...lines.map(l => l.length));
    grow(t.x, t.y - (t.fontSize || 11)); grow(t.x + longest * (t.fontSize || 11) * 0.56, t.y + 4 + (lines.length - 1) * (t.fontSize || 11) * 1.25);
  }
  x0 = Math.floor(x0 / 20) * 20 - 20; y0 = Math.floor(y0 / 20) * 20 - 20; x1 += 20; y1 += 20;
  svg.setAttribute("viewBox", `${x0} ${y0} ${x1 - x0} ${y1 - y0}`);

  const defs = el("defs", {}, svg);
  const pat = el("pattern", { id: "grid", width: 20, height: 20, patternUnits: "userSpaceOnUse" }, defs);
  el("path", { d: "M 20 0 L 0 0 0 20", fill: "none", stroke: "rgba(173,216,230,.55)", "stroke-width": 0.5 }, pat);
  el("rect", { x: x0, y: y0, width: x1 - x0, height: y1 - y0, fill: "#fff" }, svg);
  el("rect", { x: x0, y: y0, width: x1 - x0, height: y1 - y0, fill: "url(#grid)" }, svg);

  const gArcs = el("g", { stroke: "#000", fill: "none", "stroke-width": 1 }, svg);
  const gLabels = el("g", {}, svg);
  for (const a of model.arrows || []) {
    const s = T.byId.get(a.source), t = T.byId.get(a.target);
    if (!s || !t) continue;
    const sc = T.center(s.id), tc = T.center(t.id), wps = a.waypoints || [];
    const pts = [edgePoint(s, sc, wps[0] || tc), ...wps, edgePoint(t, tc, wps.length ? wps[wps.length - 1] : sc)];
    // A network connection is dashed, its pattern and colour by availability (Arrow.java); a LAN one is solid.
    const net = a.connection_type === "NETWORK", av = a.availability == null ? 1 : Number(a.availability);
    const style = net ? { stroke: av >= 0.8 ? "#0066cc" : av >= 0.6 ? "#e67e22" : "#c0392b", "stroke-width": 1.5,
      "stroke-dasharray": av >= 0.95 ? "8 4" : av >= 0.8 ? "6 6" : av >= 0.6 ? "4 8" : "2 10" } : {};
    el("polyline", { points: pts.map(p => p.x + "," + p.y).join(" "), ...style }, gArcs);
    const p = pts[pts.length - 2], q = pts[pts.length - 1], ang = Math.atan2(q.y - p.y, q.x - p.x);
    for (const d of [-Math.PI / 6, Math.PI / 6])
      el("line", { x1: q.x, y1: q.y, x2: q.x - 12 * Math.cos(ang + d), y2: q.y - 12 * Math.sin(ang + d), ...style, "stroke-dasharray": null }, gArcs);
    if (t.type === "PLACE" && (t.operations || []).length > 1) {
      const mx = q.x - 20 * Math.cos(ang), my = q.y - 20 * Math.sin(ang), ob = ang + Math.PI / 3;
      el("line", { x1: mx - 6 * Math.cos(ob), y1: my - 6 * Math.sin(ob), x2: mx + 6 * Math.cos(ob), y2: my + 6 * Math.sin(ob), stroke: "#000", "stroke-width": 2 }, gLabels);
      text(gLabels, mx + 8 * Math.cos(ob + Math.PI / 2), my + 8 * Math.sin(ob + Math.PI / 2), String(t.operations.length), { size: 11, bold: true });
    }
    if (a.label) {
      // At the middle of a straight arc, or the middle point of a bent one; moved by the label's own offset.
      const mid = pts.length === 2 ? { x: (pts[0].x + pts[1].x) / 2, y: (pts[0].y + pts[1].y) / 2 } : pts[Math.floor(pts.length / 2)];
      const lx = mid.x + (a.labelOffsetX || 0), ly = mid.y + (a.labelOffsetY || 0), w = a.label.length * 5.6 + 4;
      if (a.labelOffsetX || a.labelOffsetY)
        el("line", { x1: mid.x, y1: mid.y, x2: lx, y2: ly, stroke: "#808080", "stroke-dasharray": "3 3" }, gLabels);
      el("rect", { x: lx - w / 2, y: ly - 7, width: w, height: 14, fill: "#fff" }, gLabels);
      text(gLabels, lx, ly + 3.5, a.label, { size: 10, anchor: "middle" });
    }
  }

  const gEls = el("g", {}, svg);
  T.placeShapes = {}; T.placeFills = {};
  for (const e of els) {
    const cx = e.x + Math.floor(e.width / 2), cy = e.y + Math.floor(e.height / 2);
    if (e.type === "PLACE") {
      T.placeShapes[e.id] = el("ellipse", { cx: e.x + e.width / 2, cy: e.y + e.height / 2, rx: e.width / 2, ry: e.height / 2, fill: e.fill_color || "#fff", stroke: e.border_color || "#000" }, gEls);
      T.placeFills[e.id] = e.fill_color || "#fff";
      text(gEls, cx, e.y + e.height + 3 + 11, e.label || e.id, { anchor: "middle" });
    } else if (e.type === "EVENT_GENERATOR") {
      const g = el("g", { transform: `translate(${cx} ${cy}) rotate(${e.rotation || 0})` }, gEls);
      el("rect", { x: -e.width / 2, y: -e.height / 2, width: e.width, height: e.height, fill: "#dcffdc", stroke: "#000" }, g);
      el("path", { d: `M ${e.width / 2} 0 H ${e.width / 2 + 15} M ${e.width / 2 + 9} -3 L ${e.width / 2 + 15} 0 L ${e.width / 2 + 9} 3`, stroke: "#000", fill: "none", "stroke-width": 1.5 }, g);
      text(gEls, cx, cy + 3, "EG", { size: 9, anchor: "middle" });
      const label = (e.label || "").replace(/^EG_?/, "");
      if (label) text(gEls, cx, e.y + e.height + 3 + 8, label, { size: 9, anchor: "middle" });
    } else {
      const g = el("g", { transform: `translate(${cx} ${cy}) rotate(${e.rotation || 0})` }, gEls);
      el("rect", { x: -e.width / 2, y: -e.height / 2, width: e.width, height: e.height, rx: 3, fill: e.fill_color || "#fff", stroke: e.border_color || "#000" }, g);
      const rad = (e.rotation || 0) * Math.PI / 180, top = cy - (e.height / 2 * Math.abs(Math.cos(rad)) + e.width / 2 * Math.abs(Math.sin(rad)));
      text(gEls, cx, top + 9 + 2, NODE_LETTER[e.node_type] || "E", { size: 9, bold: true, anchor: "middle" });
      const m = /^(T_out|T_in|T)_(.+)$/.exec(e.label || "");
      if (m) text(gEls, cx, cy + 3, m[1], { size: 9, anchor: "middle" });
      else text(gEls, cx, cy + e.height / 2 + 3 + 11, e.label || e.id, { anchor: "middle" });
    }
  }
  // Badges: the node each place runs on, and a generator's initial marking with each arc's share.
  const badge = (x, y, str, fill, stroke, anchorCentre) => {
    const w = str.length * 6.2 + 8, h = 13, bx = anchorCentre ? x - w / 2 : x;
    el("rect", { x: bx, y, width: w, height: h, rx: 4, fill, stroke }, gEls);
    text(gEls, bx + 4, y + 10, str, { size: 10, bold: true, fill: stroke });
  };
  for (const p of T.places) if (p._node) badge(p.x + p.width, p.y + p.height - 7, p._node, "#e1eeff", "#285096", true);
  for (const g of T.eventGens) {
    const marks = (model.arrows || []).filter(a => a.source === g.id && a.tokens != null);
    if (!marks.length) continue;
    const total = marks.reduce((n, a) => n + Number(a.tokens || 0), 0);
    badge(g.x + g.width / 2, g.y - 13 - 3, "M₀ ×" + total, "#ede4ff", "#5a32a0", true);
    for (const a of marks) {
      const t = T.byId.get(a.target);
      if (!t) continue;
      const wps = a.waypoints || [], from = wps.length ? wps[wps.length - 1] : T.center(g.id), to = T.center(t.id);
      badge((from.x + to.x) / 2, (from.y + to.y) / 2 - 13 - 2, "×" + a.tokens, "#ede4ff", "#5a32a0", true);
    }
  }
  const gText = el("g", {}, svg);
  // "|" is a line break (TextWrapper), each line one font height below the last.
  for (const t of texts) (t.text || "").split("|").forEach((line, i) => text(gText, t.x, t.y + i * Math.round((t.fontSize || 11) * 1.25), line,
    { size: t.fontSize || 11, bold: (t.fontStyle & 1) === 1, italic: (t.fontStyle & 2) === 2, fill: argbToCss(t.color) }));

  return el("g", { id: "tokens" }, svg);
}

// Position of a token on its step (Canvas.calculateTokenPosition): along the arc between the two elements.
function along(T, from, to, p) {
  const a = T.center(from), b = T.center(to);
  if (!a || !b) return a || b;
  const arc = T.arcs.get(from + "->" + to), pts = [a, ...((arc && arc.waypoints) || []), b];
  const lens = []; let total = 0;
  for (let i = 1; i < pts.length; i++) { const d = Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y); lens.push(d); total += d; }
  let goal = p * total;
  for (let i = 0; i < lens.length; i++) {
    if (goal <= lens[i] || i === lens.length - 1) { const f = lens[i] ? Math.min(1, goal / lens[i]) : 0; return { x: pts[i].x + (pts[i + 1].x - pts[i].x) * f, y: pts[i].y + (pts[i + 1].y - pts[i].y) * f }; }
    goal -= lens[i];
  }
  return b;
}
function positionOf(T, s, phase, p) {
  switch (phase) {
    case PH.AT_EG: return T.center(s.eg);
    case PH.TO_TIN: case PH.TO_PLACE: case PH.TO_TOUT: case PH.TO_NEXT: case PH.TO_TERM: return along(T, s.from, s.to, p);
    case PH.AT_PLACE: return T.center(s.place);
    case PH.AT_TERM: return T.center(s.term);
    case PH.BUFFERED: return T.center(s.tIn);
  }
  return null;
}
// Tokens within a few pixels of each other are spread 12 px apart, centred on where they were (Canvas.spreadOverlapping).
function spreadOverlapping(pos) {
  const out = pos.map(p => ({ ...p })), done = new Array(pos.length).fill(false);
  for (let i = 0; i < pos.length; i++) {
    if (done[i]) continue;
    const group = [];
    for (let j = i; j < pos.length; j++) if (!done[j] && Math.hypot(pos[i].x - pos[j].x, pos[i].y - pos[j].y) < 6) { group.push(j); done[j] = true; }
    group.forEach((g, k) => { out[g].x += Math.round((k - (group.length - 1) / 2) * 12); });
  }
  return out;
}
