# Figure 11 — randomized priority-swap control

The ordinary Eclipse Ant launcher runs two independent, interleaved event-generator streams through the **same** `P1_Place → P2_Place → termination` process. Both places invoke `AlwaysTrueService.processToken`. P1 and P2 are separate local host processes on `192.168.1.82` under the existing `SingleHost` deployment. No remote deployment or separate result-archiving target is required.

## Randomized arrivals

The old artificial experiment generated 80 regularly spaced B tokens and then injected all 20 A tokens as one burst after 10 seconds. **That is no longer the schedule.**

Both A and B now issue individual tokens at randomized, interleaved times throughout the run:

- Workload **A**: 20 independently scheduled arrivals (generator `EG_A`, `PrioritySwap_Random_A.csv`)
- Workload **B**: 80 independently scheduled arrivals (generator `EG_B`, `PrioritySwap_Random_B.csv`)
- The 100 event times were produced with pseudorandom, shifted-exponential inter-arrival intervals: `20 + round(-220 × ln(1-U))` milliseconds, with `U` uniform on `[0,1)`. The 20 A positions were chosen by a pseudorandom shuffle of the 100 arrival positions; the remaining 80 are B. The initial xorshift32 state was `0xB16B00B5`.
- These CSV schedules were generated once and committed to GitHub. They are **randomized schedules, not new random draws on each execution**. That matters for a controlled comparison: normal and swapped priority runs receive **the same exact arrival schedules**. No Java generator or service code was altered.
- Final scheduled event: `26701 ms`. Both workloads occur in the beginning, middle, and final parts of the schedule. No 10-second mass burst.

| Condition | A (20 arrivals) | B (80 arrivals) |
|---|---|---|
| Normal (default) | v001 — higher priority | v002 — lower priority |
| Swapped | v002 — lower priority | v001 — higher priority |

Only the versions assigned to A and B change between conditions; their actual event schedules, counts, P1/P2 routes, host assignment, business operations and expiry (120 s) remain unchanged. Priority selection is non-preemptive; existing running service executions are not interrupted.

## Normal Eclipse workflow

1. Fetch/refresh the `experiment/figure11-priority-rerun` branch, and make sure no other local P1/P2 processes occupy ports 4001 and 4002.
2. Open `btsn.petrinet.ProjectLoader/PrioritySwap_TwoPlace_BuildAndRun.xml` in Eclipse and select **Ant Build**. Its default target `run-complete-workflow` does initialization, both-version rule commitment, concurrent token generation and collection, and starts P1/P2 locally.
3. For the normal condition, **no Ant arguments** are needed.
4. Once Phase 3 finishes, stop the launched processes and save the usual PetriNetAnalyzer output and timing chart **before another run**, since initialization can replace Monitor data.
5. For the swapped condition, run the **same** buildfile with `-Dpriority.condition=swapped`. Collect the same analysis and charts. There is no separate capture, ZIP, special launcher or console-log requirement.

## Analysis criteria

Check 20/20 A and 80/80 B completions, actual interleaved `GENERATED` timestamps, queue wait at P1 and P2, and end-to-end workflow durations. Compare by A/B workload identity, not version label alone because version priorities are swapped between conditions. Observe queue contention; do not infer it solely from a chart. For publication, a priority effect should be assessed with repeated runs, the fixed input schedules and recorded limitations. The Monitor's available timing records are not direct measurements of every internal enqueue/dequeue decision. No result is pre-claimed.
