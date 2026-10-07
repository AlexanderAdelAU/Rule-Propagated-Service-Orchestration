# Build and run a single-place workflow

This tutorial uses the current P1 loop model. P1 is a generic orchestration host;
its business operation is supplied by the independent
`StochasticEntryTokenService` JAR. Monitor collects observations outside the
business-token path.

![Generator sends tokens through P1 input, business computation and output routing; false loops, true terminates, and Monitor observes separately.](images/p1-tutorial.svg)

*The tutorial's explicit input/place/output roles. `true` completes the model
workflow; `false` repeats the activity. Repeated visits remain part of the same
logical workflow.*

## Open the model

1. Import the repository projects into Eclipse as described in the
   [README](README.md#run-an-example).
2. Run `com.editor.ProcessEditor` from
   [`btsn.workflowEditor`](btsn.workflowEditor/src/com/editor/ProcessEditor.java).
3. Open
   [`P1_Tutorial_Workflow.json`](btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_Tutorial_Workflow.json).
   The directory is `Workflow`, with a capital W.

Inspect these model elements:

| Element | Meaning |
|---|---|
| Event generator | Supplies workflow tokens to `T_in_P1` |
| `T_in_P1` | Single-input `EdgeNode`; receives/buffers work |
| Place P1 | `StochasticEntryTokenService.processToken`, consuming and returning `token` |
| `T_out_P1` | `GatewayNode`; applies the declared `true`/`false` routes |
| `TerminateNode` | Ends the successful path; it is not a Monitor invocation |

The logical service name is separate from the physical `P1_Place` runtime
location used by the launcher. The model service preserves the existing
stochastic processing behaviour; it is not a business implementation inside P1's
orchestration handlers. Do not assume fixed timings or a fixed number of repeat
visits from the picture. Token validity bounds the run; an expired incomplete
instance is not a successful completion.

To change the model, edit its nodes, operations or outgoing guards in the editor
and save the JSON. Preserve the service's canonical input/output contract unless
you are also changing the matching implementation and deployment definition.
Opening or playing a model does not deploy it.

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

## Understand the contract and routes

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
