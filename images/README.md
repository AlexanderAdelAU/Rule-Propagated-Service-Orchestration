# Architecture and workflow diagrams

The root README and tutorial use these editable SVG schematics. They have an
opaque white canvas, readable labels, accessible SVG titles/descriptions, and no
external image/font dependency. Shapes, text and connectors are native vector
content, not generated raster artwork.

| Diagram | Meaning and source |
|---|---|
| `rpso-architecture.svg` | Current responsibility boundaries: explicit T_in → Place P → T_out roles in a generic host, in-process invocation of a bound domain or token-operation JAR, separate Monitor collection. Based on `ServiceThread`, `ServiceHelper`, `RuleHandler` and the packaged runtime. |
| `rpso-rule-deployment.svg` | JSON topology, canonical contracts and deployment profile feed binding/rule generation. Local acknowledgements and runtime token flow are distinct. Based on `TopologyBindingGenerator` and `RuleDeployer`. |
| `financial-workflow.svg` | P1–P5 Validation → Credit/Fraud → Underwriting → Decision, with invalid/declined termination. Source: `btsn.common/ProcessDefinitionFolder/petrinet/Workflow/FinancialSystem_P1_P5_Workflow.json`. |
| `healthcare-workflow.svg` | P1 Triage, P2 Laboratory, P3 Cardiology, P4 Radiology, P5 Diagnosis and P6 Treatment, including the direct treatment path. Source: `btsn.common/ProcessDefinitionFolder/healthcare/Workflow/Emergency_Department_Patient_Workflow.json`. |
| `p1-tutorial.svg` | Explicit circular P1 between input/output transition bars: a Boolean-returning function, a false loop and true termination. All five JSON nodes/arcs are retained; observation is outside the token path. Source: `btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_Tutorial_Workflow.json`. |
| `petrinet-fork-join.svg` | Boolean-returning functionality at every P1–P4 place; T_out_P1 forks on true or terminates on false. P2/P3 forward either result to the input join before P4. The join requires both arrivals, irrespective of their Boolean values. All fifteen JSON nodes/arcs are retained and checked during generation. Source: `btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_P2_P3_P4_Fork_Join_Workflow.json`. |
| `petrinet-double-join.svg` | Explicit P1–P6 transition–place–transition units: circular places, transition bars, three-way fork at T_out_P1, input joins at T_in_P4/T_in_P6, and terminal transitions. JSON node/arc identities are retained in the SVG; generation checks every configured arc. Source: `btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_to_P6_Double_Join_Workflow.json`. |

Business process diagrams abbreviate each activity's local T_in → P → T_out
roles. The Petri-net diagrams expand the local units: each has a circular place
between input/output transition bars. Solid local arcs connect unlike node
types; dashed blue links represent RPSO's transition-to-transition publication
channels. These links are distinct from ordinary bipartite P/T-net arcs.
The input transitions perform synchronization; the bound function supplies
the place's computation; the output transitions route, fork or terminate.
The tutorial and four-place diagrams label the simple function as returning
true or false; the six-place deployment uses the separate deterministic
Boolean/carry/merge functions described in the root README. The `AND` label
denotes required arrivals at an input join, not an AND of their Boolean values.
The dot is an illustrative token marking, not a measured execution snapshot.
The diagram describes the executable RPSO model and its implementation mapping.
Host placement is specified separately by deployment metadata.

The architecture/deployment diagrams use solid blue arrows for execution/token
flow, purple dashed arrows for rule installation, grey dashed arrows for
acknowledgements, and teal dotted arrows for observation. Text and line patterns
carry the distinction as well as colour. The business JAR remains inside the
host process; separate packaging does not imply a separate network service.
Monitor's arrow represents collected observations, not a mandatory business hop
or a claim that every runtime event is streamed directly to Monitor.

To regenerate all seven SVGs after editing their standard-library Python source:

```sh
python3 images/generate_diagrams.py
```

Run from the repository root. Python is needed only for this optional
documentation task; the Ant runtime build does not use it. Inspect the rendered
SVGs after changes and check the workflow names, guards, joins and host mappings
against their source definitions.

Earlier PNG diagrams are retained as historical assets. They are not used by the
current root README or tutorial. In particular, `rule_generation.png` depicts an
older DOT pipeline, while `building_a_model.png` and `P1_Workflow_Tutorial.png`
show earlier editor models with Monitor on the drawn business path. Do not use
those images as descriptions of the migrated runtime.

## Captured execution examples

The root README also uses these unmodified user-supplied chart screenshots.
They are measured-run illustrations, not generated fixtures or regenerated data.

| Image | Capture and scope |
|---|---|
| `execution/petrinet-p1-p4-run.png` | `image(20261006-105613).png`: P1–P4 fork/join, ten root bars, elapsed/queue diamonds. |
| `execution/financial-p1-run.png` | `image(20261006-125126).png`: FinancialSystem P1 Simple, ten root bars, lower queue shading. |
| `execution/healthcare-concurrent-run.png` | `image(20261007-004520).png`: three healthcare versions, lower queue shading, right-clipped viewport excerpt. |

The captures retain earlier generic missing-measurement legends and bar widths.
The updated chart offers Wide/Narrow bars and displays unavailable-measurement
notes only when applicable to the displayed roots. No raw observation database
accompanies these captures: do not infer exact values from pixels, treat a
viewport as proof of full-run accounting, or relabel them as a newer run.
`generate_diagrams.py` regenerates only the seven SVG schematics, not these images.
The journal screenshot `image(20261007-004731).png` was a layout reference only;
its completion-order axis and percentage queue bands are not the current
combined chart's arrival-order and measured queue-maximum semantics.
