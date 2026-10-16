# Controlled priority-swap experiment (TOSEM Figure 11 replacement)

**Status: prepared, not yet run or validated on the three-machine deployment.** This tests a new controlled comparison rather than claiming to recover the lost historical 22.6 s / 1.6 s numbers. No core Java or runtime changes.

## Research question

With identical process topology, business services, host placement and token arrival schedules, does swapping the workflow version-number ranges reverse the queue-delay effect at shared hosts? This tests **non-preemptive queue priority**, not interruption of an already executing operation.

Both versions compile the **same** two-place process, `P1_Place → P2_Place → termination`, using the same `AlwaysTrueService.processToken` capability at each place. Both versions are committed **before** tokens are emitted. P1 runs on ip0 (.82), P2 on ip1 (.152); the third machine is not required for this two-shared-place control. The launcher reuses the repository's normal Ant/Monitor path, and the user starts any remote P2 host in the established way.

- **B, continuous background:** 80 tokens at 350 ms intervals, from t=0 through t=27.65 s. Generator ID `EG_BACKGROUND`.
- **A, short burst:** 20 tokens at t=10 s. Generator ID `EG_BURST`.
- **Normal:** A=v001 (lower ID, higher priority), B=v002.
- **Swapped:** A=v002 (lower priority), B=v001.
- Both conditions have the same number of tokens, schedule, P1/P2 routes, physical hosts, service JARs, expiry (120 s), and 45 s drain wait.

**Why these counts:** 20 and 80 are each within the 100-root-workflow version range. The burst is intended to create a backlog that interacts with the continuous stream; the actual contention is an experimental result, not a guarantee. If queues overflow, expiry occurs, or the background doesn't overlap the burst, report it and revise the protocol rather than silently discarding the run.

## Preflight (on the launcher machine, from Eclipse Ant)

After fetching and checking out `experiment/figure11-priority-rerun` in Eclipse, use the Ant Build dialog on:
`btsn.petrinet.ProjectLoader/PrioritySwap_TwoPlace_BuildAndRun.xml`.

1. Ensure your remote P2 (.152) host is running the matching current `main` infrastructure/service packages with the normal ThreeHost ports. Do not launch a second P2 process on the same port. P1 (.82) and Monitor are started by the launcher in its default configuration; as with the existing three-host workflow, check for port conflicts.
2. Run the target `validate-experiment` once before either condition. It must show the two version assignments and locate the two CSV schedules.
3. Check that the host runtimes and business-service packages are identical for the two conditions; note the commit SHA and the machine, JDK and network setup in your lab notes.

**New model/configuration artifacts in this branch:** `btsn.common/ProcessDefinitionFolder/petrinet/Research/PrioritySwap_TwoPlace_Workflow.json`; `btsn.common/ServiceDeploymentFolder/petrinet/PrioritySwap_TwoPlace.json`; `btsn.services/deployments/models/PrioritySwap_TwoPlaceDeployment.json`. They use existing capabilities, hosts and port assignments.

## Pilot: verify the model before the comparison

Run **normal** first as a pilot, then stop the Ant process and let all Derby locks close. If token completion is not 80/80 B and 20/20 A, or rules fail to commit, preserve the logs and send them before continuing. Do not use failed pilot measurements in the final comparison without marking them.

In the Eclipse Ant Build `Arguments` field, add:

```text
-Dpriority.condition=normal
```

Use the default target `run-complete-workflow`. It invokes the standard three-phase Ant runtime; do not use `analyse` until the workflow and collection have completed. After it says "Phase 3 Complete", stop the still-running local host processes by the normal Ctrl+C/stop action, exactly as you do for the live-change launcher.

Save the Eclipse **launcher console output** separately, including ruleset/commitment messages and any warnings.

## Record each run BEFORE starting the next

From the same Ant buildfile, run the **capture-run** target, with both arguments:

```text
-Dpriority.condition=normal -Dexperiment.id=normal-01
```

This runs the existing `PetriNetAnalyzer --all`, writes `analysis.txt` and `manifest.txt`, copies the local Monitor Derby data directory plus local launcher logs and all input files, and produces:

```text
btsn.services/target/research/priority-swap/normal-01.zip
```

Also save the **remote P2 log** and the **launcher console** (these cannot be captured from the remote machine by Ant). Take screenshots of any timing charts if available. Do not start the next run until the archive is safe and complete.

Then run **swapped** via the launcher with:

```text
-Dpriority.condition=swapped
```

After completion, stop local hosts, and run `capture-run` with:

```text
-Dpriority.condition=swapped -Dexperiment.id=swapped-01
```

The archive is `btsn.services/target/research/priority-swap/swapped-01.zip`.

**Important:** Because `priority.condition` is an Ant argument, check it before *each* launcher and capture run; the capture target cannot infer which condition last ran from the existing Derby database. Never archive a normal database with `priority.condition=swapped` or vice versa.

## Before we repeat or draft Figure 11

Send both ZIPs, the two saved launcher-console logs, the remote P2 logs and any failure notes. We will verify:
1. 80/80 and 20/20 completions under both conditions, no missing service records, and consistent token intervals.
2. Both P1 and P2 receive traffic from both versions; number of actual shared-service contention observations.
3. Per-instance end-to-end latency and queue wait, by **workload identity** B (continuous) and A (burst), not merely by `v001`/`v002` (those labels swap).
4. A's latency distribution during its burst, and B's before/during/after the burst; effect sizes, sample sizes and uncertainty.
5. Whether the new result justifies a figure, its description of non-preemptive queue ordering, and any claim about lower priority having to wait.

We should complete one **normal/swapped pilot pair** first. Only after confirming it is meaningful should we perform repetitions in counterbalanced order (e.g., normal/swapped, swapped/normal), preserving every run including transport losses and failures.

This is *not* an RQ4 live-publication test; the live-change run remains separate.

## Limitations

Ant `capture-run` runs on the launcher machine and cannot automatically retrieve remote log files. Existing Monitor measurements record arrival, invocation, and publish—not exact queue insertion/dequeue events. Therefore a latency reversal is evidence for the **end-to-end effect of priority assignment**, but individual queue-order inversion candidates cannot be adjudicated using this dataset alone. No outcome is pre-claimed.
