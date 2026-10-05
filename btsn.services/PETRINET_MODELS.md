# Running raw Petri-net models

Run `btsn.petrinet.ProjectLoader/P1_P2_Deterministic_BuildAndRun.xml` as an
Ant Build in Eclipse, using its default `run` target. It builds the JARs, prepares
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

Run the `analyse` target in the same Ant file after collection to inspect actual
measurements. Logs and working configuration are under
`btsn.services/target/petrinet-model-runtime`. Source Financial deployment
settings and databases remain separate; its launcher uses its existing profile.

The model address settings are in
`btsn.common/ProcessDefinitionFolder/PetriNetModels_Infrastructure.json` and
`btsn.services/models/InfrastructureDeployment.ruleml.xml`; keep their ip0
addresses consistent. The usual `auto`, `local` and `remote` launch properties
apply to P1, P2 and Monitor. This launcher uses the existing ServiceHelper
invocation mechanism and introduces no additional deployment protocol.

For an isolated JAR contract check, select `check-model-services` in
`btsn.services/build.xml`. The legacy stochastic implementation remains packaged
for existing models but is not selected by this model.
