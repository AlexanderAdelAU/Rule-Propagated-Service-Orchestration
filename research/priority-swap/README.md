# Figure 11 — controlled priority-queue contention

The **ordinary Eclipse Ant Build** runs two versions of the identical `P1_Place → P2_Place → termination` process, using `AlwaysTrueService.processToken` at both places. P1 and P2 run separately on the existing `SingleHost` infrastructure at `192.168.1.82`. No remote ZIP or capture target is required.

## Why this schedule creates contention

The experiment is specifically designed to test **priority overtaking**, not merely two randomly interleaved token streams.

1. **Build a low-priority backlog:** Generator B produces 30 individual randomized arrivals between **72 and 894 ms**. Under the previously observed approximately 300–350 ms P1 service-dispatch cadence, these arrivals accumulate at P1. They are *not* generated as one indivisible 30-token batch.
2. **Introduce the competing stream:** Generator A's first randomized arrival is scheduled at **2577 ms**, after B has had time to queue. Then the remaining 19 A and 50 B tokens arrive at randomized interleaved times until **31187 ms**. Both streams continue during contention.
3. **Reverse priorities without changing inputs:** The exact same committed CSV schedules are used in both conditions.

| Condition | A (20 tokens) | B (80 tokens) |
|---|---|---|
| **Normal (default)** | v001, higher priority | v002, lower priority |
| **Swapped** | v002, lower priority | v001, higher priority |

This means normal prioritizes A over already-waiting B, while swapped prioritizes B over subsequently arriving A. The first part of the experiment is deliberately asymmetric to create a backlog; **the process, token counts, schedules, service implementation and host allocation do not change between conditions**.

## Schedule reproducibility

`PrioritySwap_Random_A.csv` and `PrioritySwap_Random_B.csv` are fixed, reproducible input files, not fresh random draws on every run. The recorded pseudorandom seed is **`0xC07E2026`** (xorshift32). The first 30 B timestamps were sampled from 50–899 ms, then sorted and made unique. A's first timestamp was sampled from 2250–2599 ms. The later shared timeline was generated using random increments `40 + round(-285 ln(1-U))` milliseconds, with uniform `U` in `[0,1)`. Nineteen later A slots were randomly selected, one per successive stratum of the 69 remaining event positions, to avoid all A arrivals clustering into one block. Every CSV row is `timestamp_ms,0,1` (one token per event).

The first 30 B arrivals are intended to overload P1 without reaching its configured 50-token input buffer. Approximate queue calculations based on earlier measured service cadence are **design estimates**, not assertions about execution. Verify actual queue occupancy and timing in the analysis.

## Eclipse execution

1. Fetch and check out the latest `experiment/figure11-priority-rerun` branch on `.82`; ensure no stale P1/P2 processes are using their assigned local ports.
2. Open `btsn.petrinet.ProjectLoader/PrioritySwap_TwoPlace_BuildAndRun.xml` and use **Ant Build** with the default `run-complete-workflow` target. **No extra arguments** for normal.
3. Let initialization, token generation and data collection finish. Save the regular PetriNetAnalyzer output and timing chart **before the next run**, because Monitor data can be replaced by initialization.
4. Repeat the identical buildfile with `-Dpriority.condition=swapped`. Preserve its analyzer output and chart as well.

Do not deploy new Java code or change service handlers, queueing logic, the Analyzer or Monitor to conduct this test.

## Acceptance criteria

- **Validity:** 20 A and 80 B workflows generated/completed; no orphaned or missing visits, and no overflow/dropped-token errors.
- **Arrival overlap:** Actual `GENERATED` timestamps match the intended lead-in and overlapping streams, allowing for launcher startup jitter.
- **Contention:** Prior to an A arrival, older B tokens are still outstanding at P1. The recorded events and observed queue waits must support this, rather than assuming contention because of the CSV.
- **Priority evidence:** For the normal condition, test whether A tokens that arrived later than waiting B tokens begin P1 service sooner. In the swapped condition, compare the corresponding scheduling order and queue latencies by **A/B workload identity** rather than merely by v001/v002 version label.
- **Caveat:** `GENERATED` and `ENTER` events are not necessarily direct queue enqueue/dequeue records. Treat observed overtaking as evidence consistent with priority scheduling, not proof of exact internal dequeue decisions without queue instrumentation.

This is a deliberately overloaded, randomized and reproducible **control**. Whether it demonstrates inversion is determined by the actual runtime records, not by the schedule design alone.
