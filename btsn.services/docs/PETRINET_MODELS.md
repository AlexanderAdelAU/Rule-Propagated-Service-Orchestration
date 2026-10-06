# Running raw Petri-net models

Packaged deterministic and stochastic service JARs are grouped under
`btsn.services/target/deployment/services/models`; shared support stays in
`btsn.services/target/deployment/lib`. This grouping does not change host placement.

Run `btsn.petrinet.ProjectLoader/P1_P2_BuildAndRun.xml` as an
Ant Build in Eclipse, using its default `run-complete-workflow` target. It builds the JARs, prepares
the model configuration, checks ip0, launches local P1/P2 and Monitor when
appropriate, initializes the databases, deploys the process, sends ten tokens
and requests collection. Remote hosts must already be running with matching
model configuration.

The model uses `BooleanTokenService` at P1 and `ForwardTokenService` at P2.
P1 returns the selected boolean; P2 carries the token to termination.
Both outcomes use the same unguarded connection in this first model. This checks
execution and outcome propagation; it does not test two different guarded paths.
Monitor receives collected measurements and is outside the token path.

Set the Ant property `token.outcome` to `false` for the false case; its default
is `true`. `token.count` defaults to ten. Stop the previous local Ant run before
starting another. Local hosts remain running after collection, as in the
existing Financial launcher. Stop that Ant run to close its local hosts.

There is no random outcome, simulated service delay or service-side marking
model. Execution, queueing and elapsed times are recorded by the existing
platform. The launcher's waits allow startup, deployment and collection to finish;
they do not add a delay inside the service operation.

The original output files are written to `btsn.petrinet.ProjectLoader`:
`P1_Place.out.txt`, `P2_Place.out.txt` and `MonitorService.out.txt`.
Monitor uses its usual `btsn.common.Monitor/ServiceAnalysisDataBase` database,
so the usual analyser and charts read this run's collected measurements.
The `analyse` target in this Ant file reads that same database.
As in the original launcher, initialization purges the selected P1/P2 and
Monitor databases before the run. This replaces Monitor's previous results.
P1/P2 working configuration remains under
`btsn.services/target/petrinet-model-runtime`; source deployment metadata is
unchanged. Both model launchers import `btsn.services/model-runtime.xml` for
shared build support.

The model address settings are in
`btsn.common/ProcessDefinitionFolder/PetriNetModels_Infrastructure.json` and
`btsn.services/deployments/models/InfrastructureDeployment.ruleml.xml`; keep their ip0
addresses consistent. The usual `auto`, `local` and `remote` launch properties
apply to P1, P2 and Monitor. This launcher uses the existing ServiceHelper
invocation mechanism and introduces no additional deployment protocol.

For an isolated JAR contract check, select `check-model-services` in
`btsn.services/build.xml`. The legacy stochastic implementation remains packaged
for existing models but is not selected by this model.

## Six-place double join

Run `btsn.petrinet.ProjectLoader/P1_to_P6_Double_Join_Workflow.xml` using its
default target. It builds the current JARs, checks ip0, starts local P1-P6 and
Monitor when appropriate, initializes, deploys, sends ten tokens and collects
from all six places. The seven `.out.txt` files remain beside that launcher;
Monitor uses its usual database and analyser.

The existing true route forks into P2, P3 and P5. P4 joins P2/P3, then P6 joins
P4/P5 and terminates the business token. Monitor observes collected data outside
the token path. The existing false route terminates at P1. `token.outcome`
defaults to `true`. Service implementations preserve the incoming branch data;
the platform performs both joins and measures actual execution.
The single-path P1 input is an edge transition; the duplicate generator arrow
and its erroneous input-join label have been corrected.

| Place | Logical service | Input attributes | Return attribute |
| --- | --- | --- | --- |
| P1 | BooleanTokenService | token | token |
| P2 | BranchTwoTokenService | token | token_branch2 |
| P3 | BranchOneTokenService | token | token_branch1 |
| P4 | MergeTokenService | token_branch1, token_branch2 | token_branch2 |
| P5 | SideTokenService | token | token_branch1 |
| P6 | FinalMergeTokenService | token_branch1, token_branch2 | token |

The branch slots follow the incoming arc order in the existing model. The model
address settings are `DoubleJoinModels_Infrastructure.json` in the common
process definitions and `btsn.services/deployments/models/DoubleJoinInfrastructure.ruleml.xml`.
Keep their ip0 addresses consistent. Working place configuration is under
`btsn.services/target/double-join-model-runtime`. Stop locally launched hosts
before starting another run using these ports.
