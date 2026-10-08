# Running raw Petri-net models

Packaged Petri-net service JARs are grouped under
`btsn.services/target/deployment/services/models`; shared support stays in
`btsn.services/target/deployment/lib`. This grouping does not change host placement.

Run `btsn.petrinet.ProjectLoader/P1_P2_BuildAndRun.xml` as an
Ant Build in Eclipse, using its default `run-complete-workflow` target. It builds the JARs, prepares
the model configuration, checks ip0, launches local P1/P2 and Monitor when
appropriate, initializes the databases, deploys the process, sends ten tokens
and requests collection. Remote hosts must already be running with matching
model configuration.

All active Petri-net profiles use `StochasticService.processToken(data)`.
P1 and P2 select separate deployment instances of that same service. Each
invocation returns a random Boolean; the explicit `boolean-token` adapter
carries data and exposes the result to process guards. Both outcomes use the
same unguarded connection in this two-place model.

`token.count` defaults to ten. The older `token.outcome` launcher property is
input data only; it does not force the service result. Node and gateway types,
joins and routing are configured by the process independently of the service.
Stop the previous local Ant run before starting another. Local hosts remain
running after collection. Queueing and elapsed times are measured by the
platform; there is no simulated service delay.

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

All models use the address and fixed ports in
`btsn.common/InfrastructureDefinitionFolder/SingleHost.json`. Service placement
is selected separately from `btsn.common/ServiceDeploymentFolder/petrinet`;
runtime preparation generates matching deployment rules.
Pass `-Dhost.address=127.0.0.1` for an isolated local run. The usual `auto`, `local` and `remote` launch properties
apply to P1, P2 and Monitor. This launcher uses the existing ServiceHelper
invocation mechanism and introduces no additional deployment protocol.

For an isolated JAR contract check, select `check-model-services` in
`btsn.services/build.xml`. Former token implementations are packaged only for compatibility checks;
no active Petri-net deployment selects them.

## Six-place double join

Run `btsn.petrinet.ProjectLoader/P1_to_P6_Double_Join_Workflow.xml` using its
default target. It builds the current JARs, checks ip0, starts local P1-P6 and
Monitor when appropriate, initializes, deploys, sends ten tokens and collects
from all six places. The seven `.out.txt` files remain beside that launcher;
Monitor uses its usual database and analyser.

The existing true route forks into P2, P3 and P5. P4 joins P2/P3, then P6 joins
P4/P5 and terminates the business token. Monitor observes collected data outside
the token path. The existing false route terminates at P1. Each invocation chooses a random Boolean. The adapter preserves branch data;
the platform performs both joins and measures actual execution.
The single-path P1 input is an edge transition; the duplicate generator arrow
and its erroneous input-join label have been corrected.

| Place | Logical service | Input attributes | Return attribute |
| --- | --- | --- | --- |
| P1 | StochasticService | token | token |
| P2 | StochasticService | token | token_branch2 |
| P3 | StochasticService | token | token_branch1 |
| P4 | StochasticService | token_branch1, token_branch2 | token_branch2 |
| P5 | StochasticService | token | token_branch1 |
| P6 | FinalStochasticService | token_branch1, token_branch2 | token |

The branch slots follow the incoming arc order in the existing model. Service
placement is selected by `ServiceDeploymentFolder/petrinet/DoubleJoinModels.json`
using the same `InfrastructureDefinitionFolder/SingleHost.json` as every other
model. Runtime preparation generates its network rules from that selection. Working place configuration is under
`btsn.services/target/double-join-model-runtime`. Stop locally launched hosts
before starting another run using these ports.
