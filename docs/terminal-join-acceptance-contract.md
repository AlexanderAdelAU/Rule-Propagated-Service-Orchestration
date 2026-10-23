# BTSN — fork, terminal join and replay acceptance contract

Status: restart specification for the abandoned PR #90. This branch starts from `main`, **not** from PR #90. The source of truth for the example is the user-supplied `mytest.json` dated 23 October 2026, not an inferred diagram.

## Exact input topology

```
EG1 -- T2 (EdgeNode, T_in) -- P3 (StochasticService.processToken)
                                 -- T4 (EdgeNode, T_out) --+
                                                         |
                                                         T5 (JoinNode) -- T9 (TerminateNode)
                                                         |
EG1 -- T7 (EdgeNode, T_in) -- P6 (P6Service.test_token)  |
                                 -- T8 (EdgeNode, T_out) --+
```

The workflow JSON explicitly contains the arcs `EG1→T2`, `T2→P3`, `P3→T4`, `T4→T5`, `EG1→T7`, `T7→P6`, `P6→T8`, `T8→T5`, and `T5→T9`. T5's node type is `JoinNode` and T9's is `TerminateNode`. There is **no place/service after T5**.

## Semantics to implement (no hidden substitutions)

1. **Generator fan-out**: the outgoing arcs of EG1 determine the two destinations and child count. One scheduled logical parent `1000000` produces `1000001` along T2 to P3 and `1000002` along T7 to P6. Next parent `1010000` uses `1010001` and `1010002`. Children share the parent's business scope but remain distinct until synchronization. No manual Fork Children setting.
2. **Real routing**: each service dispatches via the binding for its actual T_out edge. T4 and T8 must feed T5; they must not separately terminate or bypass the join. The `T5→T9` arc is a legal topology-only transition-to-transition edge in this specified case.
3. **Terminal join**: T5 synchronizes the two children of the same parent without requiring a synthetic or unrelated business service. Child arrivals from different parent families never join together. The lowest child sequence ID survives for the logical completion according to the existing BTSN contract.
4. **Termination**: only after T5 has the required children does T9 terminate the logical family. For ten parents, the expected result is ten correlated join completions and ten logical workflow terminations, not twenty independent terminations.
5. **Instrumentation**: events and genealogy must unambiguously identify the ten logical parent families, twenty child executions, join correlation/consumption/survival, and ten completed logical workflows. Monitoring and canonical analysis must agree; no hard-coded report correction.
6. **Replay**: follow *only* arcs declared in this JSON. Use the recorded events as evidence of execution. A missing, contradictory, unsupported or unresolvable transition is an explicit diagnostic/failure. **Do not guess another node, invent a route, silently consume a token, or call an arbitrary fallback TerminateNode.** In particular, a host TERMINATE record before the declared terminal join is a runtime/topology mismatch requiring investigation, not something for the animator to paper over.

## Regression acceptance

- Exact T5 JoinNode and T9 TerminateNode path remains untouched; no replacement P7/MergeService is inserted.
- Ten parent events, twenty distinct child deliveries, both branches observed on their prescribed T_in and T_out arcs.
- Ten joins, each correlating `nnnnn01` with `nnnnn02`; a single lowest-ID survivor per family.
- Ten post-join T9 terminations, zero orphan children, zero unresolved edges and zero phantom animation paths.
- Baseline one-route, multi-generator, existing service-join and catalogue workflows continue to pass.
- A missing arc/endpoint or unsupported terminal-join rule fails visibly at build/deploy rather than allowing a partial run.
- End-to-end run and animation verification are required before the replacement PR can be considered for merging.

## Why PR #90 was abandoned

It mixed useful fan-out changes with unsupported assumptions about join-to-terminate semantics and animation fallbacks. The delivered runtime events (20 GENERATED children, 20 TERMINATE, 0 JOIN) prove that the specified terminal join has not been executed. Rebuild from the actual workflow/rule/host contracts rather than copying patches from that PR.
