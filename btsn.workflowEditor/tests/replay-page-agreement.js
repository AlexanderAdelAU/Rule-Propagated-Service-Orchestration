// The web replay page (docs/replay/index.html) shows each example's run as the editor does.
// Replays every example in docs/replay/examples.json with the page's own code (its timeline, not its drawing), finds where each
// token is at the same 49 recorded moments the editor's states were written for (ReplayPageStates), and requires the
// two to agree on at least 95% of token positions in every run. The few that differ are tokens caught a few
// milliseconds apart as they leave a generator or enter a place.
//
// usage: node replay-page-agreement.js <repository root> <directory of the editor's states>
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm");
const [root, statesDir] = process.argv.slice(2);
const MIN_AGREEMENT = 0.95;

// The page's replay logic (replay.js, shared by the replay page and the examples page).
const logic = fs.readFileSync(path.join(root, "docs/replay/replay.js"), "utf8");
const page = vm.createContext({ Math, Number, String, Array, Map, Set, Object, isFinite, Infinity });
vm.runInContext(logic + "\nthis.api = { buildTopology, parseEvents, buildTimeline, PH };", page);
const { buildTopology, parseEvents, buildTimeline, PH } = page.api;

// Both pages load replay.js?v=<the first 8 hex digits of its SHA-256>, so a browser holding an older replay.js
// fetches the new one whenever it changes, rather than running a new page with an old script.
const version = require("crypto").createHash("sha256").update(fs.readFileSync(path.join(root, "docs/replay/replay.js"))).digest("hex").slice(0, 8);
for (const pageName of ["index.html", "examples.html"]) {
  const html = fs.readFileSync(path.join(root, "docs/replay", pageName), "utf8");
  if (!html.includes(`<script src="replay.js?v=${version}"></script>`)) {
    console.error(`FAIL: docs/replay/${pageName} must load replay.js?v=${version} (replay.js has changed; update the version in both pages)`);
    process.exit(1);
  }
}

const runs = JSON.parse(fs.readFileSync(path.join(root, "docs/replay/examples.json"), "utf8")).examples.map(e => [e.id, e.model, e.run]);
let failed = 0;
const report = [];
for (const [name, modelPath, runPath] of runs) {
  const model = JSON.parse(fs.readFileSync(path.join(root, modelPath), "utf8"));
  // The node each place is deployed to, read as the page reads it.
  let nodes = null;
  if (model.serviceDeployment) {
    const d = JSON.parse(fs.readFileSync(path.join(root, "btsn.common", model.serviceDeployment), "utf8"));
    nodes = {};
    for (const c of d.capabilities || []) { if (c.instance) nodes[c.instance] = c.node; if (c.service && !(c.service in nodes)) nodes[c.service] = c.node; }
  }
  const T = buildTopology(model, nodes);
  const events = parseEvents(fs.readFileSync(path.join(root, runPath), "utf8"), T);
  const R = buildTimeline(events, T);
  const label = id => { const e = T.byId.get(id); return e ? (e.label || e.id) : "?" + id; };
  const where = s => s.phase === PH.AT_TERM ? "end " + label(s.term) : s.phase === PH.BUFFERED ? "wait " + label(s.tIn)
    : s.phase === PH.AT_PLACE ? "at " + label(s.place) : s.phase === PH.AT_EG ? "eg " + label(s.eg) : s.phase === PH.EXPIRED ? "expired"
    : "move " + label(s.from) + ">" + label(s.to);
  // The recorded span the replay shows: up to where every host was still recording, as the editor does.
  const first = Math.min(...R.events.map(e => e.t)), last = Math.max(...R.events.map(e => e.t));
  const pageAt = {};
  for (let k = 1; k < 50; k++) {
    const real = first + (last - first) * k / 50;
    let lo = R.start, hi = R.end;
    while (hi - lo > 1) { const m = Math.floor((lo + hi) / 2); if (R.realAt(m) < real) lo = m; else hi = m; }
    const state = new Map();
    for (const s of R.segs) { if (s.s > hi) break; if (hi >= s.e) continue; const c = state.get(s.tok); if (!c || s.s > c.s) state.set(s.tok, s); }
    pageAt[k] = new Map([...state].map(([tok, s]) => [tok, where(s)]));
  }
  const editorAt = {};
  for (const line of fs.readFileSync(path.join(statesDir, name + ".tsv"), "utf8").split("\n")) {
    if (!line) continue;
    const [k, tok, w] = line.split("\t");
    (editorAt[k] = editorAt[k] || new Map()).set(tok, w);
  }
  let same = 0, all = 0;
  for (let k = 1; k < 50; k++) {
    const e = editorAt[k] || new Map(), p = pageAt[k];
    for (const tok of new Set([...e.keys(), ...p.keys()])) { all++; if (e.get(tok) === p.get(tok)) same++; }
  }
  const agreement = all ? same / all : 0;
  if (agreement < MIN_AGREEMENT) failed++;
  report.push(`${agreement < MIN_AGREEMENT ? "FAIL" : "ok"}  ${name}: ${(100 * agreement).toFixed(1)}% of ${all} token positions agree`);
}
console.log(report.join("\n"));
if (failed) { console.error(`FAIL: the web replay page and the editor disagree on ${failed} run(s)`); process.exit(1); }
console.log(`PASS: the web replay page shows each of the ${runs.length} examples' runs as the editor's replay does (at least ${100 * MIN_AGREEMENT}% of token positions agree at 49 moments in each run)`);
