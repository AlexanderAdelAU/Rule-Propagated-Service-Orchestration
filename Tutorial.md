# Build and run a single-place workflow

This tutorial uses the current P1 loop model. P1 is a generic orchestration host;
its bound function returns `true` or `false`, supplied by the independent
`StochasticEntryTokenService` JAR. Input receipt and output routing belong to
the surrounding transitions. Monitor collects observations outside the
business-token path. Other functions can be bound at a place using their
matching contracts and deployment definitions.

![Generator sends tokens through P1 input, business computation and output routing; false loops, true terminates, and Monitor observes separately.](images/p1-tutorial.svg)

*The tutorial's explicit input/place/output roles. `true` completes the model
workflow; `false` repeats the activity. Repeated visits remain part of the same
logical workflow.*

## Open the model

1. Import the repository projects into Eclipse as described in the
   [README workspace preparation](README.md#prepare-the-workspace).
2. Run `com.editor.ProcessEditor` from
   [`btsn.workflowEditor`](btsn.workflowEditor/src/com/editor/ProcessEditor.java).
3. Open
   [`P1_Tutorial_Workflow.json`](btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_Tutorial_Workflow.json).
   The directory is `Workflow`, with a capital W.

Use **File → Load (.json)** to open the supplied definition. The following
walkthrough also shows how to reconstruct its behavior on a blank canvas.

Inspect these model elements:

| Element | Meaning |
|---|---|
| Event generator | Supplies workflow tokens to `T_in_P1` |
| `T_in_P1` | Single-input `EdgeNode`; receives/buffers work |
| Place P1 | A Boolean-returning function: `StochasticEntryTokenService.processToken`, consuming and returning `token` |
| `T_out_P1` | `GatewayNode`; applies the declared `true`/`false` routes |
| `TerminateNode` | Ends the successful path; it is not a Monitor invocation |

The logical service name is separate from the physical `P1_Place` runtime
location used by the launcher. Each completed invocation produces a fresh
Boolean independently of the arriving Boolean value; this demo implementation
selects `true` with probability 0.5. The place function produces the result and
T_out applies the declared routes. Timings and the number of repeat visits vary
between runs. Token validity bounds the run; an expired incomplete instance is
not a successful completion.

To change the model, edit its nodes, operations or outgoing guards in the editor
and save the JSON. Preserve the service's canonical input/output contract unless
you are also changing the matching implementation and deployment definition.
Opening or playing a model does not deploy it.

## Build this process in ProcessEditor

![ProcessEditor with the P1 loop on the canvas and the selected place's service and processToken operation in the Attributes panel.](images/process-editor-p1-tutorial.png)

*The supplied tutorial in ProcessEditor. Select a node or arrow to edit its
properties in the left Attributes panel. P1's service binding gives the place
its functionality; the surrounding transitions and arrows define coordination.*

### 1. Place and configure the nodes

Choose **File → New → Process Definition**, then select **PetriNet** in the
toolbar's **Type** field. Click a palette tool, then click the canvas to place
its shape. The tooltips identify **Event Generator**, **Transition** and
**Place**. Arrange these five nodes from left to right:

| Palette tool | Label | Attributes to set | Purpose |
|---|---|---|---|
| Event Generator | `P1_EVENTGENERATOR` | Rate (ms): `1000`; Version: `v001`; Fork Children: `0` | Identify the initial token source |
| Transition | `T_in_P1` | Transition Type: `T_in`; Node Type: `EdgeNode`; Buffer: `50` | Receive and buffer the single required input |
| Place | `P1` | Service: `StochasticEntryTokenService`; configure its operation below | Supply the Boolean-returning function |
| Transition | `T_out_P1` | Transition Type: `T_out`; Node Type: `GatewayNode` | Route the function's result |
| Transition | `Terminate` | Transition Type: `Other`; Node Type: `TerminateNode` | End the successful path |

Click a node to select it and use the **Attributes** panel for these settings.
Set **Transition Type** before **Node Type**, because the available node types
depend on the transition role. The editor sets the corresponding Node Value
automatically. Every label must be unique; labels become node IDs when saved.
The supplied JSON uses the internal ID `T_in_Model_Terminate` for the node
displayed as `Terminate`; a new node labelled `Terminate` provides the same
terminal role through its `TerminateNode` setting.

The generator's displayed Rate and Version are model fields. The actual run is
controlled by the generator arguments and `rule.version` in the Ant launcher;
changing these editor fields alone does not change those launch settings.

### 2. Bind functionality to P1

Select the circular **P1** node. Enter `StochasticEntryTokenService` in
**Service**. In **Operations**, enter `processToken` and click the operation
**+** button. Expand the new operation with **[+]** to configure its argument:

| Setting | Value |
|---|---|
| Operation name | `processToken` |
| Argument name | `token` |
| Argument value | `String`, matching the supplied tutorial |
| Argument type | `String`, the editor's default |
| Required checkbox (`R`) | Leave unchecked, matching the supplied tutorial |
| Returned attribute | `token`, as defined by this service's contract |

Enter the argument name and value in the operation's argument row, then click
that row's **+** button. The panel should show **processToken (1 args)**. The
returned attribute belongs to the service contract; the current operation panel
edits the operation name and inputs, rather than providing a return-attribute
field. For this single-input, non-join output, binding generation uses `token`.

This selects the already packaged Boolean function. To use different
functionality at P1, provide the corresponding service implementation, contract
and deployment binding, then select its service and operation in the model.
Entering a new service name in the editor does not create its implementation.

### 3. Connect the execution structure and routes

Use **Arrow (drag)**: press on the source node and release over the target.
Alternatively, **Arrow (click waypoints)** lets you select the source, add bends
and finish on the target. Create these five connections:

| Source | Target | Arrow label | Guard Condition | Decision Value |
|---|---|---|---|---|
| `P1_EVENTGENERATOR` | `T_in_P1` | Leave blank | Leave blank | Leave blank |
| `T_in_P1` | `P1` | Leave blank | Leave blank | Leave blank |
| `P1` | `T_out_P1` | Leave blank | Leave blank | Leave blank |
| `T_out_P1` | `Terminate` | `true` | `DECISION_EQUAL_TO` | `true` |
| `T_out_P1` | `T_in_P1` | `false` | `DECISION_EQUAL_TO` | `false` |

Select each output arrow and set **Guard Condition** and **Decision Value** in
its Attributes panel. **The arrow label is a caption; the guard fields specify
the routing condition.** Leave **Endpoint** blank for this single-operation
service. The two local arcs connect T_in → P → T_out; publication routes leave
T_out, including the returning false path.

To make the false loop readable, route it above the place using click waypoints,
or double-click an existing arrow to add a control point and drag that point.
The **Network Connection** checkbox controls the saved connection annotation
and dashed appearance. The supplied model marks the generator and true terminal
publication as network connections; its false loop remains solid. Host/channel
selection still comes from deployment configuration.

The graph now says: receive a token, invoke P1's function, terminate on `true`,
or return on `false`. Repeated loop visits belong to the same workflow instance.
Monitor is an observer and is not added as a sixth activity.

### 4. Validate and save the definition

Click **Validate** or choose **Edit → Validate**. Resolve missing or duplicate
labels, incomplete place definitions and invalid connections. This checks the
editor's structural rules; deployment and execution also check service bindings
and runtime behavior.

Use **File → Save As (.json)** and save the practice model as:

```text
btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_Editor_Practice.json
```

This keeps the supplied tutorial available for comparison. Saving creates the
JSON process definition: nodes, operations, arguments, guards and layout.
Reopen the saved file with **File → Load (.json)** to inspect the result.

## Define the architecture and contract

The process graph describes logical activities and routes. The architecture
(infrastructure) definition places their capabilities on hosts and channels;
the deployment profile selects the matching definitions and service catalog.
For this one-place tutorial, reuse the supplied architecture:

| Definition | What it supplies |
|---|---|
| [StochasticLoopModels_Infrastructure.json](btsn.common/ProcessDefinitionFolder/StochasticLoopModels_Infrastructure.json) | Numbered nodes, channel addresses/ports and capability placement; P1 hosts `StochasticEntryTokenService.processToken` |
| [StochasticLoopModels.json](btsn.common/BusinessServiceDefinitions/StochasticLoopModels.json) | The implementation class and the `token → token` service contract |
| [StochasticLoopDeployment.json](btsn.services/deployments/models/StochasticLoopDeployment.json) | The catalog, infrastructure definition and deployment rules selected by the launcher |

ProcessEditor also provides **File → New → Infrastructure Definition** for
building infrastructure definitions separately from the process canvas. This
practice model uses the existing host placement and service, so its saved process
can use the tutorial's existing deployment profile. Changing host placement
requires matching infrastructure definitions and deployment rules.

The operation contract is `token → token`. Bindings generated for this logical
service include facts with the following shape:

```xml
<Atom>
    <Rel>localDefined</Rel>
    <Ind>StochasticEntryTokenService</Ind>
</Atom>
<Atom>
    <Rel>canonicalBinding</Rel>
    <Ind>processToken</Ind>
    <Ind>token</Ind> <!-- returned attribute -->
    <Ind>token</Ind> <!-- required input -->
</Atom>
```

At runtime, input coordination supplies the named argument. `ServiceHelper`
invokes the installed implementation in-process. Output routing uses the local
RuleBase: `false` publishes back to P1's input; `true` takes the declared terminal
path. The host records those events, and the collector later sends observations
to Monitor. Monitor is not an extra activity in this loop.

## Run the process you saved

In Eclipse, open the Ant launch configuration for
[`P1_Tutorial_BuildAndRun.xml`](btsn.petrinet.ProjectLoader/P1_Tutorial_BuildAndRun.xml).
In its **Properties** tab, add this user property and select the default
**run-complete-workflow** target:

| Property | Value |
|---|---|
| `workflow.process.name` | `petrinet/Workflow/P1_Editor_Practice` |

Use the path relative to `ProcessDefinitionFolder`, without `.json`.
Keep the generator label `P1_EVENTGENERATOR` and operation `processToken` so they
match this launcher's existing settings. The initializer, collector, deployment
profile and physical target `P1_Place` stay suitable for this one-place model.

From a terminal at the repository root, the equivalent command is:

```sh
ant -f btsn.petrinet.ProjectLoader/P1_Tutorial_BuildAndRun.xml -Dworkflow.process.name=petrinet/Workflow/P1_Editor_Practice
```

The launcher reads the saved definition, generates bindings, deploys its rules
and runs it on the packaged host. The editor's **Play** button is for replaying
captured observations. Remove the property override to return to the supplied
`P1_Tutorial_Workflow` example described below. When analyzing or replaying the
practice run, use `P1_Editor_Practice.json` as the matching process definition.

## Run the BuildAndRun XML

Run
[`btsn.petrinet.ProjectLoader/P1_Tutorial_BuildAndRun.xml`](btsn.petrinet.ProjectLoader/P1_Tutorial_BuildAndRun.xml)
as an **Ant Build**, with the default `run-complete-workflow` target. Ant must run
on a JDK 15+; Ant 1.10.2+ is required. No preliminary service build is needed.

The launcher:

1. Builds the independent business JARs, shared infrastructure, generic host,
   Monitor and event generators.
2. Prepares an isolated configuration and host database directory under
   `btsn.services/target/launchers/P1_Tutorial_BuildAndRun`, using the
   [stochastic loop profile](btsn.services/deployments/models/StochasticLoopDeployment.json).
3. Generates bindings from the workflow and prepares the RuleBase configuration.
4. Starts configured local components, initializes their databases, deploys the
   workflow rules, generates tokens and collects observations.

Its `rule.version` property selects the workflow version for the run; the JSON
editor's displayed generator version is not a substitute for the launch settings.
Local/remote startup follows the configured channel address and mode overrides.
Remote components must already be running with the corresponding configuration.
Locally started components remain active after the phases finish: stop the Ant
run before starting another launcher on the same ports.

Initialization resets the selected runtime's data. Save or collect results from
an earlier run before starting a fresh initialized run. Monitor retains its usual
working directory so the analysis tools read the collected results.

## Analyze and replay observations

After collection, run the launcher's `analyse` target or run `PetriNetAnalyzer`
from `btsn.common.Monitor`, package `org.btsn.derby.Analysis`. Check generated and
completed instances, canonical place visits and any structural/temporal issues.
Loop visits are not additional workflow roots. Missing completion records must
not be interpreted as measured successful durations.

`SwingGanttChart_WithLatency_v1d` displays measured workflow and queue intervals;
`WorkflowSpatialView` displays service activity. See
[the README's interpretation guide](README.md#observe-and-interpret-a-run).

For animation:

1. Save the analyzer's console output, for example as
   `btsn.common/AnalysisFolder/PetriNet/P1_Tutorial_Workflow.txt`.
2. In ProcessEditor, open the same `P1_Tutorial_Workflow.json` that produced the run.
3. Load the saved analysis file and press **Play**.

The animator replays the captured observations. Keep the analyzer output and
workflow JSON together, and retain Monitor's `WorkflowRunMetadata` with the
analysis database when archiving a run. Historical observations without captured
process metadata are labelled accordingly; the viewer does not infer the process
from today's deployment.

## Author

Alexander Cameron
