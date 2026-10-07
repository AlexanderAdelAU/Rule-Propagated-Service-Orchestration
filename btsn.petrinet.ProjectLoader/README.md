# Petri-net model launchers

Run your usual `XXX_BuildAndRun.xml` as an Ant Build in Eclipse with Ant 1.10.2+
and a JDK 15+. Each retained launcher builds the packaged business services,
shared infrastructure, numbered hosts, Monitor and event generators automatically.
No separate `clean`/`package` step or Eclipse `bin` compilation is required.

The six main model launchers and eleven standalone utility phases import
`btsn.services/launcher-runtime.xml`. Shared JAR paths are declared once; business
JARs are collected recursively from their domain folders. Each main launcher
uses its own configuration and host databases under
`btsn.services/target/launchers/<Ant-project-name>`. Monitor retains its usual
working directory and database, so the analyser and diagrams keep reading the
same results. Output filenames and each launcher's existing token counts,
versions, delays, deployment modes and phase order are retained.

Financial launchers now live in [btsn.financial.ProjectLoader](../btsn.financial.ProjectLoader/README.md).
The existing deterministic two-place and double-join examples retain their
deterministic model profiles.
The older tutorial, fork/join and traffic-light examples select explicit
stochastic model profiles. Their business operations use the unchanged
`BaseStochasticPetriNetPlace` processing, including guards, delays and join
processing, through separate service JARs. Small adapters give each branch an
explicit result attribute; physical host placeholders contain no business code.
Model workflow definitions name these capabilities and terminate outside Monitor.
The traffic-light model retains its cyclic topology and is bounded by token
expiry rather than a successful workflow completion.

The concurrent fork launcher uses `P1_P2_ForkCompanion_Workflow` for its shorter
version. It shares the same P1/P2 capabilities as the fork version, so one
installed deployment can serve both versions without conflicting operation
bindings. Its two-place path is retained.

Standalone initializer, workflow and collector XMLs in `utility files` share one
prepared directory per family: `P1_P2_Phases`, `P1_P2_P3_P4_Phases`, or
`TrafficLight_Phases`. This preserves the databases and installed rules between
separate phases. Their process paths now use the current `Initializers`,
`Workflow` and `Collectors` folders. Stop the preceding Ant run before starting
a phase; the compatibility cleanup target waits for ports and does not kill
unrelated Java applications.

Local/remote selection and explicit overrides in the main launchers remain in
effect. Remote hosts must already be running with the matching prepared
configuration and deployed JARs. Preparation copies configuration into the
generated runtime and leaves source deployment metadata unchanged.

Each main launcher exposes `prepare-runtime` and `analyse` for those phases
alone. Developers can run `btsn.services/build.xml` with `check-launchers` to
configure and prepare all 22 Financial/model Ant entry points, check JAR-only classpaths and
resolve their workflow contracts without starting services. The check reuses
the JARs it has just built; ordinary launcher execution always builds them.
