# Architecture and workflow diagrams

The root README and tutorial use these editable SVG schematics. They have an
opaque white canvas, readable labels, accessible SVG titles/descriptions, and no
external image/font dependency. Shapes, text and connectors are native vector
content, not generated raster artwork.

| Diagram | Meaning and source |
|---|---|
| `rpso-architecture.svg` | Current responsibility boundaries: local rules and input/output coordination in a generic host, in-process business JAR invocation, separate Monitor collection. Based on `ServiceThread`, `ServiceHelper`, `RuleHandler` and the packaged runtime. |
| `rpso-rule-deployment.svg` | JSON topology, canonical contracts and deployment profile feed binding/rule generation. Local acknowledgements and runtime token flow are distinct. Based on `TopologyBindingGenerator` and `RuleDeployer`. |
| `financial-workflow.svg` | P1–P5 Validation → Credit/Fraud → Underwriting → Decision, with invalid/declined termination. Source: `btsn.common/ProcessDefinitionFolder/petrinet/Workflow/FinancialSystem_P1_P5_Workflow.json`. |
| `healthcare-workflow.svg` | P1 Triage, P2 Laboratory, P3 Cardiology, P4 Radiology, P5 Diagnosis and P6 Treatment, including the direct treatment path. Source: `btsn.common/ProcessDefinitionFolder/healthcare/Workflow/Emergency_Department_Patient_Workflow.json`. |
| `p1-tutorial.svg` | Current StochasticEntryTokenService loop and direct termination, with observation outside the token path. Source: `btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_Tutorial_Workflow.json`. |
| `petrinet-double-join.svg` | Live deterministic P1–P6 model: three-way fork, P2/P3 input join at P4, P4/P5 input join at P6, false termination at P1, and collected runtime measurements outside the token path. Source: `btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_to_P6_Double_Join_Workflow.json`. |

Process diagrams abbreviate each activity's local T_in → P → T_out roles. They
show logical publications and routing alternatives, not a full classical
bipartite Petri net, physical network placement, or measured execution evidence.
The editor's JSON model supplies the detailed transition/place structure.

The architecture/deployment diagrams use solid blue arrows for execution/token
flow, purple dashed arrows for rule installation, grey dashed arrows for
acknowledgements, and teal dotted arrows for observation. Text and line patterns
carry the distinction as well as colour. The business JAR remains inside the
host process; separate packaging does not imply a separate network service.
Monitor's arrow represents collected observations, not a mandatory business hop
or a claim that every runtime event is streamed directly to Monitor.

To regenerate all six SVGs after editing their standard-library Python source:

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
`generate_diagrams.py` regenerates only the six SVG schematics, not these images.
The journal screenshot `image(20261007-004731).png` was a layout reference only;
its completion-order axis and percentage queue bands are not the current
combined chart's arrival-order and measured queue-maximum semantics.
