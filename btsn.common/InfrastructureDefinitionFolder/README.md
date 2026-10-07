# Infrastructure definitions

An infrastructure definition brings the logical service contracts onto physical
nodes: it declares their channels, addresses, port ranges and available service
operations. Choose the definition for your application before deploying a process.

The editable JSON files are grouped by domain:

| Folder | Definitions |
|---|---|
| [healthcare](healthcare) | [Healthcare_Infrastructure.json](healthcare/Healthcare_Infrastructure.json) |
| [financial](financial) | [FinancialSystem_Infrastructure.json](financial/FinancialSystem_Infrastructure.json), [InfrastructureDefinition.json](financial/InfrastructureDefinition.json) |
| [petrinet](petrinet) | [PetriNetModels_Infrastructure.json](petrinet/PetriNetModels_Infrastructure.json), [StochasticLoopModels_Infrastructure.json](petrinet/StochasticLoopModels_Infrastructure.json), [ForkModels_Infrastructure.json](petrinet/ForkModels_Infrastructure.json), [DoubleJoinModels_Infrastructure.json](petrinet/DoubleJoinModels_Infrastructure.json), [TrafficLightModels_Infrastructure.json](petrinet/TrafficLightModels_Infrastructure.json), [P1_Tutorial_LocalInfrastructure.json](petrinet/P1_Tutorial_LocalInfrastructure.json) |

`InfrastructureDefinition.json` is the existing generic-named copy of the
financial definition. Both files are retained with their original contents.

## Edit and run

In the workflow editor, choose **File → New → Infrastructure Definition** or
**File → Open → Infrastructure Definition**. The infrastructure file chooser
starts in this folder unless you have previously selected another directory.
Open the appropriate domain subfolder and save your definition there.

The launchers select a definition using its domain and filename without `.json`:

```text
-infrastructure healthcare/Healthcare_Infrastructure
-infrastructure financial/FinancialSystem_Infrastructure
-infrastructure petrinet/P1_Tutorial_LocalInfrastructure
```

The P1 launcher exposes this selection as the Ant property
`infrastructure.definition.name`. Deployment profiles use a path relative to
`btsn.common`, such as
`InfrastructureDefinitionFolder/petrinet/P1_Tutorial_LocalInfrastructure.json`.
Runtime preparation and portable host releases copy this folder alongside the
process definitions.

Saving the JSON and generating its configuration are separate steps. **Generate
Configuration** writes canonical service bindings and generated deployment rules;
keep the selected [deployment snapshot](../../btsn.services/deployments) consistent
with the editable definition. The [P1 tutorial](../../Tutorial.md#1-define-the-infrastructure)
walks through both steps and a complete run.

## Where the other parts belong

Follow a model from deployment settings through routing to observations using
these three locations:

| Location | Purpose |
|---|---|
| `InfrastructureDefinitionFolder/{healthcare,financial,petrinet}` | Editable physical node and capability definitions |
| [ProcessDefinitionFolder](../ProcessDefinitionFolder) | Process topology, operations, guards and workflow documentation |
| Monitor analysis folders and exported results | Observations produced by process runs |

The nine infrastructure JSON files previously stored directly in
`ProcessDefinitionFolder` have moved here. Generated RuleML, canonical bindings
and deployment snapshots continue to have their own runtime roles and locations.
