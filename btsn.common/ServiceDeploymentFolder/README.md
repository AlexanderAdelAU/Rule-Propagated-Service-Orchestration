# Service deployments

A service deployment assigns business operations to physical orchestration nodes
and fixed port slots. It contains service names, operations, return attributes and
arguments. The selected [physical infrastructure](../InfrastructureDefinitionFolder/README.md)
supplies every address, channel and numeric port.

| Domain | Service deployments |
|---|---|
| Financial | [FinancialSystem.json](financial/FinancialSystem.json) |
| Healthcare | [Healthcare.json](healthcare/Healthcare.json) |
| Petri-net | [PetriNetModels.json](petrinet/PetriNetModels.json), [StochasticLoopModels.json](petrinet/StochasticLoopModels.json), [ForkModels.json](petrinet/ForkModels.json), [DoubleJoinModels.json](petrinet/DoubleJoinModels.json), [TrafficLightModels.json](petrinet/TrafficLightModels.json), [P1_Tutorial.json](petrinet/P1_Tutorial.json) |

The Petri-net deployments select different service functions on the same fixed
nodes. For example, P1 can run a Boolean, stochastic entry or stochastic join
function while retaining the shared P1 primary port. Each run selects one service
deployment; a runtime operation has one configured implementation.

## Edit and run

Choose **File → New → Service Deployment** or **File → Open → Service Deployment**
in the workflow editor. The physical-node table previews `SingleHost.json` and is
read-only. **Choose infrastructure...** previews another physical arrangement.
Edit **Node capabilities** and select **Port Slot**: `0` is the node's primary
port; `1` is its second fixed port, where defined. Save in the appropriate domain
subfolder. **Generate Bindings** writes canonical service contracts.

A profile under `btsn.services/deployments` selects `catalog`, `infrastructure`
and `serviceDeployment`. Every active catalogue operation must have a matching
service placement and canonical contract. Runtime preparation generates the
network RuleML from that selection before building the master service rulebase.
Changing a service function or its contract does not allocate new ports or change
IP addresses. Two simultaneous operations on one node require different fixed
slots; invalid slots, duplicate endpoints and conflicting contracts fail validation.

The editor's infrastructure preview is not stored in the service deployment.
The deployment profile selects the physical definition for execution, so choose
the same physical definition in the profile when preparing a distributed layout.
The [P1 tutorial](../../Tutorial.md) demonstrates the physical definition,
service placement, process design and measured results together.
