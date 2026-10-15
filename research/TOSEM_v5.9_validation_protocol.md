# TOSEM v5.9 — empirical validation protocol

**Status:** planning/audit only. No experimental results are implied by this document.
**Starting revision:** `b4a097785bcd8c9fde7f71a6ef8c4731d3612f8e` (main; PR #54 host reliability fixes).
**Working branch:** `research/tosem-validation-v59`.
**Constraints:** Ant only; preserve P1–P6 identical generic host handlers; keep Monitor out of the token path; do not change business-service logic to obtain timing differences.

## Scientific question and current evidence gap

Property 3 of the paper requires, for every logical workflow w and every event e, that the **actually applied** rule version equal the workflow's root version.

Current Petri-net instrumentation writes `ruleVersion` using `VersionConstants.getVersionFromSequenceId(tokenId)`. This records the *expected* version, not the source rule fragment used by the host.

`ServiceThread.parsePayLoad()` reads `ruleBaseVersion` from the incoming XML header, constructs `RuleFolder.<version>/<operation>/Service.ruleml`, selects/caches the knowledge base, and calls `oojdrew.parseKnowledgeBase()` before rule queries. These are currently distinct facts but not independently recorded together in the Analyzer.

A check of version-compatible visited places and join families is necessary but insufficient when a shared host/place is present in both versions.

## Phase 0 — baseline, unchanged hosts

1. Record exact Git commit SHA and all three host executable JAR hashes, Java versions, OS, addresses, ports, date/time and infrastructure/deployment JSON checksums.
2. Run current live-change workflow on the existing three-machine deployment, **without** instrumentation changes.
3. Preserve raw launcher log, independent logs from **all** P1–P6 host processes, local event stores, Monitor collection outputs, PetriNetAnalyzer report and input data/DB snapshots.
4. Keep complete failure and incomplete-workflow evidence rather than deleting or filtering it.

Launcher file: `btsn.petrinet.ProjectLoader/P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange.xml`.
From its folder, the documented invocations are:

```text
ant -f P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange.xml
ant -f P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange.xml analyse
```

**Do not run a new host package on only some machines.** A comparison of instrumented and uninstrumented host versions would be invalid.

## Phase 1 — independent version-selection evidence

Add a structured record at the host's actual knowledge-base parse/selection boundary (after successful parse, before executing queries), not solely in the token-construction instrumentation. Preserve:
- token ID, workflow/root ID, service identity, operation, host/port, local event/sequence identifier;
- **expected_version**: decoded from token ID;
- **declared_version**: read from the incoming message header;
- **selected_version**: key used to select the host rule base;
- **rule_fragment_digest**: content hash of the concrete compiled fragment successfully parsed;
- rule parse and query status (success/failure), timestamp and any discrepancy.

Do not label selected version as "applied version" until the evaluation boundary itself has been verified. Trace whether the rule engine is shared across threads or whether another message can alter it between parse and query. If shared, include sufficient locking or a query-bound identity/fingerprint to prove what was evaluated, or narrow the claim to "selected and parsed."

Store this information independently of the token-ID-derived `ruleVersion` field. If integrated with Monitor, use a *new* field or relation; do not replace the existing field or modify clock alignment/queue measurement semantics.

All six `btsn.rpso.places.pN/src/org/btsn/handlers/{ServiceThread,RuleHandler,PetriNetInstrumentationHelper}.java` files have identical Git blob SHAs at the starting commit. Keep them byte-for-byte synchronized after any change.

## Phase 2 — correctness and negative controls

- Known-good v001 and v002 tokens on shared P1/P3/P4: expected = declared = selected = evaluated; exactly one attributable record per actual evaluation.
- Token ID in v002 range with forged v001 message header: deliberately mismatched token and selected fragment must be **detected**; define whether admission must reject it.
- Unregistered version: reject at host admission; an attempted rejection must be distinguishable from a successfully evaluated event.
- Cross-workflow / cross-version fork-join contributions: no join must consume a different family/version.
- Repeated and nested forks and circulating visits: distinguish separate join occurrences and preserve lineage, without falsely requiring termination for a circulating process.
- Concurrent v001/v002 requests at the same host and operation: verify no cross-thread rule-engine state contamination.

Use controlled test inputs; don't inject synthetic timing delays into the published reference measurements.

## Phase 3 — fuller evaluation

Run at least three *different types* of live change over generic hosts: branch redistribution (current P2→P6), conditional guard update, and fork/join topology change. Keep old instances active, unchanged contracts, and predeclared standby capability when required.

Report per-run publication duration, generated/completed counts by version, missing/duplicated messages, identity violations, actual-applied-version mismatches, host/queue latency distributions, and effect on older-version work. Separate `safety` (no mixed-version evaluation) from `liveness` (workflow completion) and `transport reliability` (lost UDP datagrams).

Do not silently discard failed runs. Record any excluded run and the reason.

## Acceptance criteria for revising the manuscript

- No observed mis-version evaluation under the specified assumptions across independent three-host repetitions, with deliberately malformed-version tests correctly classified.
- The evidence checker independently compares token identity against selection/evaluation evidence; it must not infer both sides from the same token ID.
- Every claim is traceable to a committed source revision, exact Ant invocation, deployment configuration, raw data and an executable verification script/test (Ant/Java).
- The paper limits claims to established scope; failures and transport losses are reported.
- Figure 11 placeholders are replaced with figures reproducible from original measurements.

## Current status

- [x] GitHub access and branch isolation confirmed.
- [x] Locate runtime version selection and token-derived transition instrumentation.
- [x] Confirm six P1–P6 handler/instrumentation implementations are currently identical.
- [ ] Collect untouched baseline three-machine run.
- [ ] Implement independently auditable rule-selection/evaluation record and automated negative tests.
- [ ] Run all relevant Ant regression suites.
- [ ] Deploy synchronized host packages and run three-machine validation.
- [ ] Update paper tables, statistics and plots from archived raw evidence.
