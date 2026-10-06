# Healthcare build and run definitions

All retained Ant launchers use generic numbered hosts, independently packaged
business-service JARs, the shared infrastructure JAR, Monitor and common event
generators. They build through the existing `btsn.services` tooling. No legacy
healthcare place project or Eclipse `bin` output is on their classpaths.

Run the selected XML file as an **Ant Build** in Eclipse with Ant 1.10.2+ and a
JDK 15+. Paths resolve from the build file's directory, including `ProcessTests`.

| Build file | Default behaviour |
|---|---|
| `Emergency_Department_BuildAndRun.xml` | Initialize P1–P6 and Monitor, run ten patients, collect observations |
| `Federated_Radiology_BuildAndRun.xml` | Initialize P4 and Monitor, run federated requests, collect observations |
| `Triple_Workflow_Emergencey_Department_Concurrent.xml` | Run the patient, canary and federated workflows concurrently and collect all three versions |
| `Queue_Priority_BuildAndRun.xml` | Run three isolated, controlled queue-priority experiments and write evidence and timelines |
| `ProcessTests/Triage_Initializer.xml` | Start configured P1 and Monitor components and initialize their databases |
| `ProcessTests/Triage_Workflow.xml` | Start configured P1 and Monitor components, execute ten triage requests and collect observations; initialization is optional |
| `ProcessTests/Triage_Collector.xml` | Start configured P1 and Monitor components and collect existing v001, v002 and v003 measurements |

The standalone `Triage_CanaryTest_BuildAndRun.xml` has been removed. The concurrent
application retains its canary workload and its existing JSON definition.

## Triage process tests

The three process-test launchers import `ProcessTests/triage-runtime.xml` to share
the current runtime configuration, packaged classpaths and phase commands.
`Triage_Workflow.json` contains a logical TriageService operation followed by a
terminal transition. `Healthcare_Infrastructure.json` places that operation on
P1. Monitor receives collector observations separately from the patient path.
Initialization and collection use P1's generic infrastructure adapters and v999
administration tokens. Workflow and collection operations have separate settings.

| Build file | Target for already running components |
|---|---|
| `Triage_Initializer.xml` | `trigger-all-initializers-sequential` |
| `Triage_Workflow.xml` | `phase1-init-only`, `phase2-workflow-only`, `phase3-collect-only` |
| `Triage_Collector.xml` | `performance-collector-trigger` |

For a clean triage run, select **`run-with-initialization`** in
`ProcessTests/Triage_Workflow.xml`. It starts the configured components once,
initializes databases, executes triage and collects measurements. Its default
`run-complete-workflow` target retains the previous optional-initialization behaviour.
Use `validate-environment` to build and check the runtime without sending events,
`analyse` after collection to analyse the Monitor database, and `help` for options.

The default launch mode is `auto`: a component starts locally when the configured
`ip0` address belongs to the current machine. Override with `triage.mode` and
`monitor.mode` set to `local` or `remote`. Remote components must already be
running. Phase-only targets always use existing components. Locally launched
components remain running after the phases finish; stop the Ant run before
starting another launcher on the same ports. The obsolete direct-to-bin compile,
blocking sequential-start and global Java-kill targets have been removed.

Typical workflow options are `rule.version`, `token.count`, `target.operation`,
`patient.id` and `provider.id`. Collector options are `query.version` (one version
or a comma-separated list) and `collector.operation`. `init.version` and
`collector.version` default to v999; `rule.version` defaults to v001.

The launcher changes leave execution handlers, scheduling and business logic
unchanged. Shared `btsn.services` build organisation remains a separate cleanup.

## Controlled queue-priority experiment

Run **`Queue_Priority_BuildAndRun.xml`** as an Ant Build. It builds the current
service/infrastructure JARs and generic P1 JAR, then exercises P1's unchanged
`EventReactor` through real loopback UDP packets. It has its own copied loader
settings and output directory; it does not initialize or write healthcare or
Monitor databases, deploy rules, or execute clinical business operations.

The test uses one controlled consumer in place of the full `ServiceThread`.
It proves the production queue's selection policy under a known backlog, rather
than claiming an end-to-end healthcare priority result. All tokens share one
reactor, port and synthetic `controlledWork` operation. Completed-join priority
is excluded: every probe token is a normal root token.

1. A v003 blocker starts and waits on a bounded gate.
2. Four more v003 tokens are submitted, then two v002, then two v001. Each token
   is acknowledged by observed queue admission before the next is sent.
3. The gate opens only after all eight token identities are confirmed waiting.
4. The blocker finishes; both v001 tokens must execute next, followed by v002,
   then v003. Within each version, lower sequence IDs must run first even though
   they arrived in reverse order. The queue must drain with no lost tokens.

This is priority overtaking of **waiting** work. The scheduler does not interrupt
the running operation. Eight queued tokens are enough to prove contention;
filling the configured capacity of 50 would add rejection risk without improving
the ordering evidence. Failure to reach a gate or the expected order fails Ant.

The default is three runs. Set `-Dprobe.repeats=10` for more repetitions or
`-Dprobe.port=0` to select an available loopback UDP port (the default). The JVM
and its worker/UDP resources stop when the test finishes; no external host is
needed. Ant prints each run's confirmed backlog, actual execution order and PASS
summary. The handler's verbose stdout/stderr goes to `target/queue-priority/runtime.log`
and `runtime-errors.log`; those paths are printed before the test starts, including
when a failed assertion stops the run.

After success, the launcher opens `run-1/timeline.html` in the default browser.
Set `-Dprobe.open.results=false` to disable automatic opening; systems without a
desktop print the file path. This graph contains the probe's nine tokens. The
ordinary Monitor chart and Petri analyzer continue to show the previous real
healthcare run because this isolated experiment does not collect into Monitor.
The two views have different data sources. Each run writes the following under
`target/queue-priority/run-N/`:

| Output | Meaning |
|---|---|
| `report.txt` | PASS evidence, exact arrival/execution orders and release time |
| `tokens.csv` | Monotonic timestamps for admission, dequeue and controlled execution |
| `queue.csv` | Observed waiting-queue occupancy after admission/dequeue |
| `timeline.html`, `timeline.svg` | One row per token, arrival order, horizontal waiting/execution intervals on a common millisecond axis; blocker-release line and queue-occupancy plot |

Enqueue timestamps bracket admission from immediately before the synchronized
production method; CSV queue wait is therefore an upper bound including admission
overhead. The graph's grey interval extends to execution start and also includes
the small dequeue-to-start dispatch interval. This distinction is recorded rather
than calling those timestamps exact internal enqueue instants. Coloured intervals
measure controlled work, not clinical service execution.

## Measured workflow graph

After collecting a real healthcare run, open the existing Monitor timing chart
and select **View > Measured Workflow Timeline**. The new view uses the analyzer's
canonical root families: one row per GENERATED workflow, from its generation
timestamp to its canonical completion timestamp, on a shared absolute time axis.
For healthcare, the completion boundary is the business TerminateNode; older
Monitor-ending workflows retain the analyzer's successful Monitor boundary.
Fork children do not become additional workflows. Incomplete or invalid-timestamp
rows have no fabricated duration. Administration (v999) is omitted.

The default **Workflow Elapsed Time and Queue Wait** combines two measurements
in the familiar version lanes and chronological arrival positions:

- Coloured bar height: measured GENERATED-to-canonical-completion elapsed time.
- Black diamond beside each bar: maximum **observed service-visit queue wait**
  across that root family, including nested fork children.
- **View > Queue Display > Lighter Queue Bars** replaces diamonds with narrower
  bars in a lighter shade of the version colour. Each queue bar stands beside its
  solid elapsed-time bar, from the same baseline on the same lane scale.
  **Diamonds** restores the original presentation and remains the default.
- Each version lane has its own labelled millisecond scale, so short-route versions
  remain readable alongside longer routes. Both queue display styles within a lane
  share that lane's scale. Read axis values when comparing absolute times across
  versions; equal bar heights in different lanes do not mean equal durations.
- **View > Y-Axis Scale > Shared Milliseconds** restores one common scale when
  comparing absolute durations visually. **Scale Each Version** is the default.
- Bar width indicates neither execution duration nor overlap; horizontal position
  is arrival rank. Scale limits include both bars and queue markers and stay fixed
  when limiting the displayed arrival range.

The diamond or lighter queue bar is an independent measurement, not a stacked
portion of the elapsed bar or a workflow waiting fraction. Parallel branch waits are not added, and queue maxima
are not subtracted from elapsed time. Longer or shorter routes can change both
metrics, so this figure alone does not prove priority ordering or queue pre-emption.

An X on a lane baseline means the elapsed interval is unavailable (incomplete,
invalid clock ordering, or legacy data without canonical events). Missing queue
measurements have no queue glyph; valid zero waits have a diamond or a lighter
horizontal line at the baseline, depending on the display mode.
Exact repeated visit observations contribute once; null, negative or conflicting
waits are excluded, with valid and invalid visit counts in tooltips and exports.
A maximum may therefore describe only the valid observed visits. For old runs
without GENERATED events, arrival order falls back to recorded workflow starts;
elapsed time stays unavailable. Canonical genealogy prevents orphan branch tokens
from becoming extra root rows. Administration (v999) is omitted.

PNG/PDF and LaTeX/TikZ follow the selected queue style and axis scales, with matching
legends and captions. The LaTeX table and text summary retain absolute times
in milliseconds.
For a paper, the suggested caption is:

> Bars show measured workflow elapsed time; diamonds show maximum observed
> service-visit queue wait, including fork branches. Queue markers do not represent
> total workflow waiting time. Each version uses its own millisecond scale; compare
> axis values, not bar heights, across versions.

For the lighter bar presentation, use:

> Solid bars show measured workflow elapsed time; lighter bars beside them show
> maximum observed service-visit queue wait, including fork branches. Queue bars
> do not represent total workflow waiting time. Each version uses its own
> millisecond scale; compare axis values, not bar heights, across versions.

Choose **View > Service Queue Timings** for measured waiting times at each shared
host operation. Each operation has separate v001/v002/v003 comparisons on the same
millisecond scale. Bars show the mean queue wait; diamonds show the 95th percentile.
The Statistics tab includes sample count, mean, median, P95 and maximum, and its
data can be exported to CSV. Median averages the two middle values for even sample
counts; P95 uses the nearest-rank definition. Different operations at the same
service remain separate, as do different physical hosts executing the same operation.

The view reads recorded service-visit queue times and captured service/host
identities. Repeated observations of the same visit are counted once. Missing,
negative or conflicting wait values are omitted; an unknown or ambiguous host is
also omitted instead of guessed. Omission and duplicate counts are displayed.
Older records with a physical place in `serviceName` can still be grouped without
captured metadata. Records containing only a version label cannot establish a host.

These queue distributions help compare waiting at a shared operation; they do not
establish execution order or prove priority selection. The controlled priority
probe supplies that evidence separately. The measured workflow timeline continues
to show generation-to-completion durations without estimating a critical path or
adding parallel branch times. Scheduling, handlers and measurement ingestion are
unchanged.

Regression check: `ant -f btsn.services/workflow-runtime.xml check-measured-timeline`.
Queue/legacy-observation check: `ant -f btsn.services/workflow-runtime.xml check-queue-view`.

Combined figure check: `ant -f btsn.services/workflow-runtime.xml check-combined-workflow`.
