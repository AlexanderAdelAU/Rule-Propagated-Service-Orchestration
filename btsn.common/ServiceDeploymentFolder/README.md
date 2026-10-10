# Service deployments

A service deployment places reusable service operations on physical orchestration
nodes and fixed port slots. The selected [physical infrastructure](../InfrastructureDefinitionFolder/README.md)
supplies every address, channel and numeric port.

Three choices remain independent:

| Choice | Definition |
|---|---|
| Incoming acceptance, buffering or synchronization | Incoming transition in the process definition |
| Business service and operation | Service catalogue; selected by the service deployment |
| Outgoing gateway and routing guards | Outgoing transition and arcs in the process definition |

The infrastructure implements gateway behaviour; the process chooses where to
use it. Selecting a service does not restrict the supported incoming or outgoing
node types. Not every arbitrary combination is a valid process.

## Reusable operations and deployment instances

`service` identifies a reusable service implementation and its business contract.
An optional `instance` identifies one deployment of that operation. Use distinct
instances when the same service operation is placed on several hosts. A process
PLACE keeps `service` and adds `serviceInstance` to select its deployment.
Legacy definitions without instances retain their original service identity.

For example, all six traffic-light hosts now select `StochasticService.processToken`.
The process uses separate instance IDs while retaining its existing input joins,
output forks, XOR guards and retry paths. The service returns a random Boolean;
it does not synchronize inputs, create branch IDs or choose destinations.

The stochastic placements explicitly select `invocationAdapter: "boolean-token"`.
This adapter maps the Boolean result into the existing token response format,
using the placement's input names and return attribute as its orchestration-facing
contract. It carries distinct original business values without repeatedly nesting
old adapter responses in retry loops; token genealogy remains in infrastructure.
The service catalogue describes the separate `data -> decision` Boolean contract.
No adapter is applied to other services implicitly. Their existing arguments and
return contracts remain intact. Business operations with several inputs remain
supported without treating every such operation as a join implementation.

| Domain | Service deployments |
|---|---|
| Financial | [FinancialSystem.json](financial/FinancialSystem.json) |
| Healthcare | [Healthcare.json](healthcare/Healthcare.json) |
| Petri-net | [PetriNetModels.json](petrinet/PetriNetModels.json), [StochasticLoopModels.json](petrinet/StochasticLoopModels.json), [ForkModels.json](petrinet/ForkModels.json), [DoubleJoinModels.json](petrinet/DoubleJoinModels.json), [TrafficLightModels.json](petrinet/TrafficLightModels.json), [P1_Tutorial.json](petrinet/P1_Tutorial.json) |

## Edit and run

Choose **File → New → Service Deployment** or **File → Open → Service Deployment**
in the workflow editor. The physical-node table previews `SingleHost.json` and is
read-only. **Choose infrastructure...** previews another physical arrangement.
The **Service** column selects the reusable function; **Instance** selects its
deployment identity. Use alphanumeric instance IDs starting with a letter.
**Invocation Adapter** is optional; `boolean-token` applies only to the Boolean
contract described above. **Return Attribute** and **Arguments** define the
orchestration-facing instance contract when an adapter is selected.

Select **Port Slot**: `0` is the node's primary port; `1` is its second fixed port,
where defined. **Generate Bindings** writes a separate canonical contract for
each instance. In the process editor, select that same **Deployment instance**
on the PLACE; incoming and outgoing transition types are edited separately.

A profile under `btsn.services/deployments` selects `catalog`, `infrastructure`
and `serviceDeployment`. Runtime preparation generates the endpoint RuleML and
instance contracts before building the master service rulebase. Duplicate instance
operations, conflicting contracts, invalid slots and duplicate endpoints fail
validation. Changing service function or gateway arrangement does not allocate
new ports or change IP addresses.

The editor's infrastructure preview is not stored in the service deployment.
The deployment profile selects the physical definition for execution.

## Checks

From the repository root:

```sh
ant -f btsn.services/build.xml clean check check-launchers check-hosts check-model-services check-healthcare-services
ant -f btsn.workflowEditor/build.xml clean jar
```

`check-launchers` includes `ServiceSeparationCheck`: six instances of one
stochastic operation, repeated placement of a non-Boolean service, installed
instance contract isolation, Boolean adaptation and bounded retry payloads.
It also checks that both workflow parsers preserve all ten existing routing/node
types in either transition position (100 parser combinations). These are parser
preservation checks, not claims that all combinations are valid runtime models.
The existing launcher, host, financial and healthcare contract checks remain.


The editors use the selected deployment's active business catalogue as their
source of service and operation choices. In the process Attributes panel,
select the deployment, service, operation and matching instance; its input
aliases and result are displayed from that instance contract. These fields
cannot be independently typed in the process editor. Unknown legacy names
remain visible as unresolved until explicitly corrected.

## From a process to its catalogue

Every business process names its service deployment, and every service
deployment names its catalogue:

```
ProcessDefinitionFolder/financial/Workflow/FinancialSystem_P1_P5_Workflow.json
  "serviceDeployment": "ServiceDeploymentFolder/financial/FinancialSystem.json"
      "catalog": "BusinessServiceDefinitions/financial/FinancialSystem.json"
```

The catalogue is shared, not copied: one catalogue serves every process of its
domain. Process JSON also stores each operation's returnAttribute; these
declarations survive editor load/save. For a new diagram, use **Choose service
deployment...** to record the reference. The deployment editor records the
catalog reference. A runtime Deployment.json profile must select that same
catalogue. `check-launchers` fails if a launcher's business process lacks the
reference, or if its deployment does not name an existing catalogue, and checks
each process against a profile that selects its own deployment.

Initializers and collectors under `ProcessDefinitionFolder/common` (and the
labelled healthcare copies) call host administration services, not catalogue
services, so they carry no service deployment.

Unknown operations, mismatched inputs/results and incompatible adapters block
binding generation and deployment preparation. Process service-contract errors
also block the editor's normal save; diagram structure warnings remain separate.
The deployer validates process instance contracts against the runtime Deployment.json
profile before generating process rules. A compatible profile may reuse the same
process; the editor reference does not lock a process to a physical placement.
Routing types remain separate selections; opening Attributes does not rewrite
an existing type to match a filtered list.

Run ant -f btsn.workflowEditor/build.xml clean jar check-contracts to rebuild
the editor and test its catalogue choices, actual Swing selection behaviour,
contract rejection and JSON round trips. CI runs this alongside the five
existing service suites.
