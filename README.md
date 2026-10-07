# Rule-Propagated Service Orchestration (RPSO)

RPSO executes Petri-net models and distributed business processes using the same
orchestration architecture. It separates **process coordination** from
**functionality at each place**: input transitions receive and synchronize tokens,
a place invokes its bound function, and output transitions route the result.

**P1, P2, …, Pn are generic positions, not fixed business functions.** A place can
return a simple Boolean, perform a financial calculation, or process a clinical
result. The chosen service supplies its meaning; the process model supplies the
connections and routing rules. Functions conform to the declared input/output
contract, while deployment selects their implementations and hosts.

Start with a Boolean-returning Petri-net example, then build towards the
Financial and healthcare workflows. The repository also provides a workflow
editor, Ant build-and-run launchers, and observation/analysis tools.

![RPSO execution sequence with light blue activation bars: T_in receives and buffers a token, synchronizes required inputs and invokes P; P returns its result; T_in hands it to T_out, which routes or terminates the workflow.](images/rpso-execution-sequence.svg)

*The execution pattern within a generic host. T_in receives and synchronizes
inputs, P performs the bound functionality, and T_out routes the result. Shaded
activation bars show responsibility for one invocation; their lengths do not
represent measured time.*

## From the architecture pattern to a running service

Here is an example of how the components of the architecture pattern come
together to implement a real-time service workflow. A token arrives at an input
transition, the place invokes its bound service function, and an output
transition uses the result to continue or complete the process. The interactions
in the sequence diagram now become a running example.

We present the workflow as a **Petri net**: circular places identify the bound
functions, transition bars coordinate their execution, and arrows show the
possible routes taken by tokens. We begin with a single Boolean-returning
service, extend it into a parallel fork-and-join workflow, then give the same
architecture financial and healthcare functionality.

### One place: a function and its execution structure

We begin with one service so we can follow a token through a complete execution
unit. A decision to repeat or finish makes the relationship between input
receipt, function invocation and output routing visible in one small model.

![A circular P1 place between input and output transition bars. Its function returns true or false; the output transition terminates on true and loops on false.](images/p1-tutorial.svg)

In the [single-place tutorial](btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_Tutorial_Workflow.json),
**P1 performs a function whose result is `true` or `false`**. The function produces
its own result; it does not simply copy the arriving token's logical state.
The transitions supply the execution behavior around that function:

| Model role | Execution responsibility | Tutorial behavior |
|---|---|---|
| **T_in** — input transition | Receive and buffer tokens; synchronize required inputs where a join is declared | Accept the initial token or a returning token |
| **P** — place with bound functionality | Invoke the selected operation with its declared inputs | P1 returns `true` or `false` |
| **T_out** — output transition | Apply routing rules; publish, fork or terminate | `true` ends the workflow; `false` returns to T_in_P1 |

Thus **T_in → P → T_out** maps to **receive/synchronize → invoke function →
route/publish**. A generic orchestration host implements the whole unit. The
place's function and the transitions' coordination responsibilities are separate.
Run [P1_Tutorial_BuildAndRun.xml](btsn.petrinet.ProjectLoader/P1_Tutorial_BuildAndRun.xml)
as an Ant Build; [Tutorial.md](Tutorial.md) explains editing and running it.

### Four places: Boolean functionality with a fork and join

With the pattern for one service established, we can connect several execution
units. This example introduces parallel branches and a join: the fabric must
deliver work to two functions and bring their results together before the next
function can run.

![P1–P4 shown as circular places between input and output transition bars. P1 true forks to P2 and P3, their arrivals join before P4, and each place produces its own Boolean result.](images/petrinet-fork-join.svg)

The [P1–P4 model](btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_P2_P3_P4_Fork_Join_Workflow.json)
uses the same simple Boolean-returning functionality at **all four places**.
Each completed invocation produces its own `true` or `false` result, irrespective
of the arriving Boolean value. The model's routing rules determine whether that
result affects the next path:

| Place | Function result | What its surrounding transitions do |
|---|---|---|
| P1 | A fresh `true` or `false` | T_out forks to P2/P3 on `true`, or terminates on `false` |
| P2 | A fresh `true` or `false`, independent of P1's value | Forward either result to P4 through an unguarded publication |
| P3 | A fresh `true` or `false`, independent of P1's value | Forward either result to P4 through an unguarded publication |
| P4 | A fresh `true` or `false`, independent of the two input values | T_in waits for both P2/P3 arrivals; T_out forwards either result to final termination |

**The join synchronizes arrivals, not Boolean truth.** P4 does not require both
incoming values to be `true` and does not compute their Boolean AND. `AND` on the
input-transition bar denotes the required input arrivals. A result can be `false`
without preventing an unguarded onward publication.

Implementation detail: the packaged Boolean functions use
[`BaseStochasticPetriNetPlace.evaluateGuard`](btsn.common/src/org/btsn/base/BaseStochasticPetriNetPlace.java)
to select a fresh result (default probability of `true`: 0.5). This implements
the place's Boolean function. The transitions receive inputs and route its result
according to the model.

Run [P1_P2_P3_P4_BuildAndRun.xml](btsn.petrinet.ProjectLoader/P1_P2_P3_P4_BuildAndRun.xml)
to execute this model. Its captured results appear under
[Petri-net fork/join execution](#petri-net-forkjoin-execution) below.

Circles are places; bars are transitions. Solid local arcs connect each
transition–place–transition unit. Dashed blue links represent publication between
units in executable RPSO notation; these transition-to-transition links are
separate from the arcs of an ordinary bipartite P/T net. Token dots are illustrative
markings, not measured execution snapshots. Monitor observes outside the token path.

### Give the same positions domain functionality

The first two models make coordination easy to follow by using simple Boolean
functions. We can now give the same positions a business purpose: bind functions
that accept domain data and return domain results, then declare their contracts
and routes. The following examples show what changes when those functions
perform financial or clinical work. Their business activity boxes abbreviate
the same **T_in → P → T_out** unit.

| Example | Functionality supplied at places | Coordination supplied by the fabric |
|---|---|---|
| Boolean Petri-net model | Produce `true` or `false` | Conditional routing, fork, buffering and input join |
| Financial application | Validate, check credit/fraud, underwrite and decide | Fork checks, synchronize their results and route declared outcomes |
| Emergency department | Triage, perform diagnostic operations, diagnose and treat | Select direct/diagnostic paths, fork diagnostics and synchronize results |

P1–Pn denotes the general model positions. This repository currently packages
six numbered host projects; deployment determines which functions they run.

### Financial loan application

A loan application gives the fork and join pattern a concrete business purpose.
Credit and fraud checks supply different information about the same application;
underwriting needs both results to decide how processing should continue.

![Validation forks to Credit Check and Fraud Check; Underwriting joins both inputs and routes to Decision or early termination. A top-right legend expands a rounded Credit Check activity into T_in, its service function at P, and T_out.](images/financial-workflow.svg)

Validation rejects invalid applications or forks into Credit Check and Fraud
Check. Underwriting joins their named results. `approved` and `conditional`
proceed to Decision; `declined` terminates early. See
[the Financial application guide](btsn.common/FinancialApplication.md) for contracts.

### Emergency department

A patient workflow adds a choice of paths and a larger set of required inputs.
This example shows how the same execution pattern supports a route selected by
triage and synchronizes several diagnostic results before the next function
runs.

![Triage forks to Laboratory, Cardiology and Radiology, which join at Diagnosis; Treatment also accepts direct triage.](images/healthcare-workflow.svg)

Triage either publishes directly to Treatment or forks to Laboratory, Cardiology
and Radiology. Diagnosis waits for all three named results. Treatment accepts the
diagnosis path or the direct triage path through distinct operations, then ends
the patient workflow. See [the healthcare mapping](btsn.services/docs/HEALTHCARE.md)
for contracts and host assignments.

### Further topology: six places and two joins

The domain examples give the places business meaning. We can also extend the
coordination structure itself: this six-place model connects two joins, so the
result of one synchronized activity becomes an input to another. Functions that
carry and merge data deterministically make that dependency easier to trace.

![Six explicit transition–place–transition units. P1 forks to P2, P3 and P5; P4 joins P2/P3, P6 joins P4/P5 and terminates.](images/petrinet-double-join.svg)

The
[six-place double-join definition](btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_to_P6_Double_Join_Workflow.json)
binds its six places to small deterministic token operations:

| Place | Bound service | What its operation does | Output attribute and transition route |
|---|---|---|---|
| P1 | `BooleanTokenService` | Read the supplied `outcome` (default `true`); record the Boolean value and routing decision | `token`; T_out forks to P2/P3/P5 on `true`, or terminates on `false` |
| P2 | `BranchTwoTokenService` | Carry the incoming token data unchanged | `token_branch2` → P4 |
| P3 | `BranchOneTokenService` | Carry the incoming token data unchanged | `token_branch1` → P4 |
| P4 | `MergeTokenService` | Combine the two input objects into a JSON `branches` array after T_in synchronizes P2/P3 | `token_branch2` → P6 |
| P5 | `SideTokenService` | Carry the incoming token data unchanged | `token_branch1` → P6 |
| P6 | `FinalMergeTokenService` | Combine the P4/P5 input objects into a JSON `branches` array after T_in synchronizes them | `token`; T_out records workflow termination |

**These six operations are deterministic.** The generator supplies P1's outcome
through `token.outcome`; the default is `true`. P2/P3/P5 forward data and P4/P6
combine it. The output names above are payload attributes carrying JSON objects.
This additional deployment illustrates other place functionality: carrying and
combining data rather than generating a fresh Boolean at every place. Its
behavior differs from the Boolean-function examples above.

The generic fabric performs the forks, input synchronization and publication;
the model services carry and combine the token data. P4 becomes eligible after
both P2/P3 inputs are available, while P6 requires P4/P5. The topology constrains
causal order; the running hosts determine when those steps actually execute.

Run
[P1_to_P6_Double_Join_Workflow.xml](btsn.petrinet.ProjectLoader/P1_to_P6_Double_Join_Workflow.xml)
as an Ant Build with its default target. It builds the JARs, prepares the selected
deployment, initializes, deploys, sends ten tokens and collects observations.
`token.outcome` defaults to `true`; set it to `false` to exercise termination at
P1. Another deterministic example is
[P1_P2_BuildAndRun.xml](btsn.petrinet.ProjectLoader/P1_P2_BuildAndRun.xml), using
Boolean and Forward token operations. See
[the model guide](btsn.services/docs/PETRINET_MODELS.md) for profiles and contracts.

This is live token execution with measured queueing, local execution, join
waiting and workflow elapsed time. The deterministic services introduce no
simulated processing delays; launcher waits support startup and collection.
Monitor reconstructs the generated workflow families and the same analyzer,
timing chart and spatial view can inspect their observations. ProcessEditor can
then replay that captured execution on the topology. Observed timing is distinct
from a guarantee to meet real-time deadlines.

These examples execute on the same generic host machinery. Their diagrams show
configured paths; the captured runs show measured execution. Neither a topology
nor one run establishes a guarantee to meet real-time deadlines.

## Architecture: coordination and business meaning

The examples have shown one execution pattern supporting different functions
and process structures. We can now look behind those models to see how the
platform assembles them. The diagram and table below locate the process
definition, service contract, deployment, execution machinery and observation
tools, and explain how their responsibilities fit together.

![Process definitions install local rules in generic hosts, which invoke separate business JARs and supply observations to Monitor.](images/rpso-architecture.svg)

*Responsibility boundaries in the current Java implementation. Bound service
JARs are invoked in-process inside a numbered host. The dashed path configures
local rules; the dotted path carries collected observations outside the business
workflow.*

| Layer | Responsibility | Repository artefacts |
|---|---|---|
| Process model | Places, transitions, fork/join structure, guards and termination | `btsn.common/ProcessDefinitionFolder` |
| Service contract | Logical service identity, operations, named inputs and returned attribute | `BusinessServiceDefinitions`, `ServiceAttributeBindings` in `btsn.common` |
| Deployment | Map capabilities to implementation classes, hosts and channels | [Physical infrastructure](btsn.common/InfrastructureDefinitionFolder/README.md), [service deployments](btsn.common/ServiceDeploymentFolder/README.md) and `btsn.services/deployments/{healthcare,financial,models}` |
| Generic execution fabric | Transport, buffering, synchronization, version selection, invocation and publication | `btsn.rpso.places.p1`–`p6`, shared infrastructure |
| Business computation | Domain objects, calculations and decision symbols | `btsn.common/src/org/btsn/business`, packaged as independent service JARs |
| Observation | Collect records, reconstruct workflow families and display measurements | `btsn.common.Monitor` |

**Synchronize required inputs** means waiting for any remaining inputs after
the first token has been received and buffered. At a join, all declared inputs
must be available before P is invoked; for a single-input operation, that
requirement is immediately satisfied. T_in's activation ends after P's result
has returned and been handed to T_out. T_out's activation ends after routing
and publication, or after termination has been recorded. These are logical roles
within the host, which remains available for subsequent tokens.

A business service consumes its named inputs and returns a result. Where routing
is conditional, the result includes a symbol such as `approved` or `declined`.
The service interprets business meaning; the fabric matches the symbol against
declared routes and resolves the destination. Logical service identity is
separate from physical placement: P1 can host a clinical, financial or model
capability according to the selected deployment.

The execution pattern is **T_in → P → T_out**:

- **T_in** receives and buffers inputs, accumulating the required named values
  for joins. Readiness also depends on version, validity and available capacity.
- **P** invokes the bound business operation under the local execution capacity.
- **T_out** applies the installed routing rules, publishes fork children or
  records a business termination.

These are semantic roles within the local execution machinery. The business
activity boxes abbreviate a complete **T_in → P → T_out** execution unit; the
Petri-net examples above expand their transition and place nodes explicitly.
The host implements the whole unit, with its bound operation giving P its
computational meaning.

There is no central engine making every runtime routing decision. Hosts use
local rule fragments and communicate through tokens. This does not imply absence
of distributed coordination, unlimited scalability, or a proven workflow
soundness property. Shared-host queues, transport and deployment remain relevant.

## Rule deployment and token execution

Those architectural responsibilities become operational when a model is
deployed to its hosts. Follow the path from the workflow definition and service
bindings to installed local rules, then to the tokens that activate the bound
functions. This connects the diagrams above to the steps performed by a
build-and-run launcher.

![JSON workflows, contracts and deployment profiles feed local rule installation; host acknowledgements precede the normal workflow start.](images/rpso-rule-deployment.svg)

*Rule installation and runtime token flow are separate paths. Local installation
acknowledgements are not a global atomic-commit protocol.*

`TopologyBindingGenerator` derives operation bindings from the workflow.
`RuleDeployer` compiles/distributes versioned RuleML fragments to the participating
host operations and waits for local acknowledgements with retries in the normal
deployment path. Launchers that explicitly skip deployment require previously
installed matching rules. RuleBase version selection does not by itself pin the
business implementation binary; deploy the matching JARs and configuration too.

Runtime XML payloads carry token identity, workflow version, target operation,
named business attributes, validity and instrumentation fields. A fork creates
physical child tokens within one logical workflow family. Joins accumulate the
required inputs for that family; the analyzer uses recorded generated roots and
parent/child genealogy rather than treating every child as a new workflow.

| Component | Current role |
|---|---|
| `EventReactor` / `Scheduler` | Receive packets and maintain the local eligible-work ordering |
| `ServiceThread` | Validate the selected contract/version, coordinate inputs, invoke and publish |
| `RuleHandler` / OOjDREW | Install local RuleML and evaluate rule queries |
| `ServiceHelper` | Resolve and invoke the installed business implementation |
| `EventPublisher` | Send the resulting payload to the declared channel |

Priority applies to **waiting eligible work**. It does not interrupt a running
business operation. Different path lengths or different host operations can have
different queue waits without demonstrating priority overtaking. For controlled
same-queue evidence, run
[Queue_Priority_BuildAndRun.xml](btsn.healthcare.ProjectLoader/Queue_Priority_BuildAndRun.xml);
[its guide](btsn.healthcare.ProjectLoader/README.md#controlled-queue-priority-experiment)
explains the observed-backlog probe and its separate output files.

## Execution examples

Once the bindings and rules are installed, we can run the services and observe
how the configured paths execute. The following captures connect the earlier
models to measured workflow durations and queue waits on the Java execution
fabric. They retain the chart settings and measurements used when captured;
timing depends on the machine and run.

### Petri-net fork/join execution

Return to the four-place example and follow its ten generated workflow roots.
The chart groups each root with its fork children, letting us examine the
elapsed time of the complete workflow family and its largest observed visit
queue wait.

![Captured P1–P4 fork/join run: ten root workflows with elapsed-time bars and independent queue-wait diamonds.](images/execution/petrinet-p1-p4-run.png)

*A run of [P1_P2_P3_P4_BuildAndRun.xml](btsn.petrinet.ProjectLoader/P1_P2_P3_P4_BuildAndRun.xml).
Ten root workflows are shown in arrival order. Bars measure elapsed time from
generation to business completion; diamonds show the maximum observed
service-visit queue wait across each root's family, including fork children.
Children are reconstructed through genealogy, rather than drawn as extra root
workflows. The lane maximum in this run is 1,499 ms.*

### Financial service execution

This financial capture isolates the Validation function at P1. It lets us
inspect a domain service using the same measurements as the Boolean model,
before adding the remaining checks and decisions of the full loan workflow.

![Captured FinancialSystem P1 Simple run: ten root workflows with lighter queue-wait overlays inside red elapsed-time bars.](images/execution/financial-p1-run.png)

*A run of [FinancialSystem_P1_Simple_BuildAndRun.xml](btsn.financial.ProjectLoader/FinancialSystem_P1_Simple_BuildAndRun.xml),
the single-place Financial example. Ten application workflows are shown; lighter
lower shading marks the maximum observed visit queue wait. The lane maximum is
247 ms. This is the P1 example, not the full P1–P5 loan-application workflow.*

### Concurrent healthcare execution

The previous captures each show one workflow version. Here, several healthcare
processes run concurrently, bringing different routes and service demands into
the same observation view. Read the process labels and lane scales together to
understand which measurements can be compared directly.

![Captured concurrent healthcare viewport: v001 Federated Radiology, v002 Triage CanaryTest and v003 Emergency Department Patient workflows, with independently scaled lanes.](images/execution/healthcare-concurrent-run.png)

*A captured viewport of the concurrent healthcare run. The three versions share
global arrival ranks, so a blank position in one lane can belong to another
version. Each lane uses its own measured maximum: 624, 1,014 and 5,329 ms.
The screenshot is clipped at the right edge; it is an excerpt, not a full-run
token count. Different routes and service loads also affect queue waits, so this
image alone does not establish priority overtaking at a shared queue.*

These captures precede the wider-bar menu and clearer measurement notes described
below. Their generic “queue-only observations” legend describes the glyph used
when queue wait is known but workflow elapsed time is unavailable; it does not
state that tokens have been excluded. New charts show this note only when such
observations are present. The Petri-net and Financial captures each visibly show
ten measured elapsed-time bars; run completeness should be checked against the
analyzer's generated-root and completion counts.

## Observe and interpret a run

The captures provide a visual overview. To assess a run, connect those pictures
back to the recorded roots, service visits and completion events. The tools below
let us check whether the workflows completed, trace where they spent time and
replay their observed paths on the model.

Monitor is an **observation service outside the active business-token path**.
An explicit business `TerminateNode` defines completion in the migrated workflows;
collector arrival at Monitor is a separate observation boundary. Initializer and
collector commands are administration traffic, normally using v999.

The tools in `btsn.common.Monitor/src/org/btsn/derby/Analysis` include:

| Tool | Use |
|---|---|
| `PetriNetAnalyzer` | Generated roots, fork genealogy, join consumption, canonical place visits, completion and structural/temporal checks |
| `SwingGanttChart_WithLatency_v1d` | Workflow elapsed/queue figure, measured timeline, service queue comparisons, and publication exports |
| `WorkflowSpatialView` | Service/host activity in a spatial view |

The combined chart offers **Diamonds** and **Lower Queue Shading** from
**View → Queue Display**. The bar shows measured workflow elapsed time; the marker
or lighter lower portion shows the **maximum observed service-visit queue wait**
across that workflow family. This is an independent measurement, not total
workflow waiting or an additive decomposition into waiting and service time.

**View → Y-Axis Scale → Scale Each Version** is the default: each version lane
scales to its maximum measured workflow duration. Select **Shared Milliseconds**
to compare absolute durations by bar height across versions. **Workflow arrival
rank** on the x-axis counts roots in generation order across all versions;
equal spacing does not mean equal time between arrivals. Time is measured on
the y-axis, in milliseconds; bar width does not encode duration. Missing
observations remain unavailable rather than being plotted as measured zero.

**View → Bar Width → Wide (Publication)** is the default for a fuller figure,
closer to the earlier presentation. Select **Narrow** for the thinner bars.
Both choices preserve arrival positions, measured heights, lane scales and
lower queue shading, and apply to PNG, PDF and TikZ exports. The footer reports
how many observed roots the selected **Display Range** shows. This count describes
the plotted data; a narrow viewport may still require horizontal scrolling.
A dashed queue outline means the wait is known but the elapsed interval is
unavailable; the chart retains that root without inventing a completion time.
For a full figure rather than a viewport screenshot, select **Display Range →
Show All**, then use **File → Export Chart to PNG** or the PDF/TikZ export.

New runs capture their process definitions in
`btsn.common.Monitor/WorkflowRunMetadata`; charts and exports display that context.
Keep this metadata with `ServiceAnalysisDataBase` when archiving results. Older
runs without matching metadata show `Process not captured`.

To animate observations, run `com.editor.ProcessEditor` from
[btsn.workflowEditor](btsn.workflowEditor/src/com/editor/ProcessEditor.java), open
the matching workflow JSON, load the saved analyzer output, then press **Play**.
This replays captured observations; it does not launch the distributed workflow.

## Project layout and build support

We have followed a model from its service bindings through execution and
analysis. The project map below shows where to find each part when working with
the repository: the definitions to edit, the implementations to package, the
launchers to run and the tools to inspect the results. The build commands then
provide entry points for preparing the packaged runtime.

| Project | Purpose |
|---|---|
| `btsn.common` | Shared source, business implementations, contracts, physical infrastructure, service deployments, process definitions and rules |
| `btsn.services` | Service packaging, shared Ant builds, deployment profiles and checks |
| `btsn.rpso.places.p1`–`p6` | Generic numbered orchestration hosts |
| `btsn.common.Monitor` | Collection, reconstruction and visualization |
| `btsn.common.eventgenerators` | Shared workflow and administration generators |
| `btsn.healthcare.ProjectLoader` | Healthcare launchers and process tests |
| `btsn.financial.ProjectLoader` | Financial launchers |
| `btsn.petrinet.ProjectLoader` | Petri-net model launchers and utility phases |
| `btsn.workflowEditor` | Model editor and observation animator |

All domains share the physical network and fixed ports in
[`InfrastructureDefinitionFolder`](btsn.common/InfrastructureDefinitionFolder/README.md).
Business operations are assigned to those nodes in
[`ServiceDeploymentFolder`](btsn.common/ServiceDeploymentFolder/README.md).
Their routing and guards belong in
[`ProcessDefinitionFolder`](btsn.common/ProcessDefinitionFolder); the analysis
folders hold observations from executing those models.

`btsn.services` contains build support, not business implementation or execution
handlers. Service JARs are grouped under
`btsn.services/target/deployment/services/{healthcare,financial,models}`; support
libraries accompany them under `target/deployment/lib`. Host placement is selected
separately by deployment metadata. Eclipse `bin` outputs are not inputs to the
packaged workflow runtime. Runtime databases, installed rule copies, run metadata
and chart exports are local outputs excluded from Git.

For packaging/checks without starting a workflow:

```sh
ant -f btsn.services/build.xml check
```

For a portable numbered-host JAR and ZIP:

```sh
ant -f btsn.rpso.places.p1/build.xml
```

See [shared build support](btsn.services/README.md),
[portable releases](btsn.services/docs/PLACE_RELEASES.md), and
[Petri-net model profiles](btsn.services/docs/PETRINET_MODELS.md).
The repository supplies the runtime libraries, including OOjDREW and embedded
Derby; the normal Ant workflow does not require Maven or Python.

The current diagrams are editable SVGs. Their source models, conventions and
regeneration command are documented in [images/README.md](images/README.md).

# License

The licensing terms for the software and accompanying documentation follow.

**Copyright (c) 2025 [Alexander Cameron]**

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to use, copy, modify, merge, and distribute the Software for non-commercial purposes, subject to the following conditions: the above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

**Commons Clause Restriction:** The grant of rights under this license does not include the right to Sell the Software. "Sell" means providing the Software, or any derivative work, to third parties for a fee or other consideration, or offering a product or service whose value derives substantially from the Software's functionality. For commercial licensing enquiries, contact [your email].

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED.

## Author

Alexander Cameron
