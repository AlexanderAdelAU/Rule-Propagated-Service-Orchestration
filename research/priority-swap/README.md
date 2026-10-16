# Figure 11 — controlled priority-swap experiment

This is a **new controlled comparison**, not a reconstruction of the historical 55-workflow Figure 11 data. The experiment uses the normal Eclipse Ant launcher, Monitor collection, PetriNetAnalyzer and Gantt chart. There is **no extra archive/capture target** and no requirement to save the Eclipse console as a separate `Run_*.txt` file.

## Experiment design

Both workflows use the **same** `P1_Place → P2_Place → termination` process, with `AlwaysTrueService.processToken` at both nodes. P1 is on ip0 (`192.168.1.82`) and P2 is on ip1 (`192.168.1.152`) using the existing `ThreeHost` infrastructure. The third host is not needed. The priority mechanism is **non-preemptive**: it selects among queued tokens and does not interrupt an already running service.

- **B (background):** 80 tokens, one every 350 ms, from 0 to 27.65 s (`EG_BACKGROUND`).
- **A (burst):** 20 tokens at 10 s (`EG_BURST`).
- **Normal:** A is v001 (higher priority), B is v002.
- **Swapped:** A is v002, B is v001.

The topology, host allocation, business services, arrival schedules, expiration time (120 s) and post-run wait (45 s) stay unchanged between conditions. The assignment of workflow version numbers is the only intended change. Both sets of rules are deployed before any workflow tokens are emitted. Contention must be **observed**, not assumed.

## Run in Eclipse

1. Fetch and check out `experiment/figure11-priority-rerun`. Keep the same compatible infrastructure and business-service runtime on both machines. Start the remote P2 host (`192.168.1.152`) in the established way; do not start a duplicate P2 process on that port.
2. In `btsn.petrinet.ProjectLoader`, select `PrioritySwap_TwoPlace_BuildAndRun.xml` → **Ant Build**. The default target, `run-complete-workflow`, performs the ordinary initialization, rule deployment, token generation and collection.
3. **Normal run:** use no additional Ant arguments. The default `priority.condition` is `normal`.
4. Wait for **Phase 3 Complete**, stop the local launcher as usual, and run your existing PetriNetAnalyzer and Gantt-chart tools. Save the analysis output and chart(s) **before the next run**; initializing the next run may overwrite Monitor data.
5. **Swapped run:** run the *same* buildfile with this single Ant argument:

   ```text
   -Dpriority.condition=swapped
   ```

6. After Phase 3, collect and save the swapped-run analysis and charts in the same way.

There is no `capture-run`, `experiment.id`, special ZIP output, or additional tool to execute. The `Run_*.txt` file from your normal Eclipse workflow is not required; if a run fails, the console and remote-host logs may be useful for diagnosis.

## What to send for analysis

For **each condition**, send PetriNetAnalyzer output and the resulting Gantt chart (PNG or other exported image). Any Service Queue Timings measurements and workflow timeline data are helpful when available. We will check completion counts (20 A + 80 B), shared-host contention, queue waits, latencies, and whether reversing the version priorities reverses the observed latency effect.

Interpret the data by **workload identity** A/B, not just v001/v002: the version assignments switch between conditions. Monitor's current measurements do not directly expose exact internal enqueue/dequeue events, so the resulting figures can support an end-to-end priority effect but not prove each individual scheduling decision. Avoid a paper claim until the measurements are verified.
