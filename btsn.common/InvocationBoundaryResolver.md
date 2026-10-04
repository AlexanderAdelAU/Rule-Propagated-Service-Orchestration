# Invocation boundary resolver

This candidate starts at the verified Financial recovery baseline
`4b8a2d01d0e1e5cf1e585d0b79f2a7e9228688cc`.

The orchestration agent continues to select the service, operation, inputs and
return attribute from its rulebase. Immediately before reflection, `ServiceHelper`
resolves the selected runtime operation to its deployed business implementation.

## Lookup

`BusinessServiceDefinitions/Deployment.json` selects the existing authoritative
catalogue, infrastructure definition, generated deployment facts and registered
initialization/collection operations. These paths are configuration, rather than
business identities compiled into the agent or resolver.

The resolver joins the capability's node/channel/base port to the generated
`activeService` and `boundChannel` facts. The runtime class name is taken from
those facts; it is not inferred from a P-node name. The catalogue supplies the
logical service, operation, ordered inputs, return attribute and implementation
class. The installed versioned operation rulebase must contain that logical
service's `localDefined` fact and matching canonical bindings before invocation.

Missing, duplicate, inactive or contradictory business mappings raise an error.
There is no fallback to a physical business adapter. Only initialization and
collection operations explicitly registered by the selected rule files keep
their existing direct invocation. This candidate configures the Financial
deployment; unrelated Petri-net business/simulation definitions require their
own deployment metadata before using this boundary.

The existing five-argument `ServiceHelper.process` overload uses deployment
metadata without a versioned-rulebase check. The six-argument overload used by
`ServiceThread` preserves and validates its selected rulebase version.

The default common directory is `../btsn.common`, matching the existing project
layout. A host with another layout can set `-Dbtsn.common.dir=<common-directory>`.
Deploy the manifest and all selected metadata/rule files alongside the common
classes. Resolver instances retain a deployment snapshot; restart services after
changing this metadata for this first candidate.

## Scope and verification

The resolver was introduced in `47ecac4` through the new common resolver and
identical small changes in the six P1–P6 `ServiceHelper.java` copies. Its live
Stage-5 run completed v001 15/15 and v002 40/40 with structural and temporal
results `[OK]`. The analyzer retained five P1 start-order inversion observations.

Step 1 removes concrete service implementation dependencies from all six hosts.
P1–P6 place classes and their existing constructor signatures remain as inert
physical host placeholders. They have no imports, operation methods or service
implementation inheritance. The same rule applies regardless of the service
selected in deployment metadata.

The service implementation formerly inherited by P6 is preserved outside the
host as `org.btsn.services.StochasticPlaceService`. It uses the unchanged
`BaseStochasticPetriNetPlace` processing code and accepts a caller-supplied
runtime identity, capacity and delay. Its three-String constructor works with
the invocation boundary's existing constructor contract. No physical host name
is compiled into that implementation. The existing Financial implementations
also remain unchanged in common; the host code refers to none of them.

Every `ServiceThread.java`, all other existing infrastructure handlers, Monitor,
business implementations, workflow definitions, existing deployment definitions
and the Stage-5 launcher remain byte-for-byte unchanged from the recovery
baseline. Resolver and helper code are unchanged from `47ecac4`. All `build.xml`
files are unchanged. Service JAR packaging remains deferred for discussion
after the completed six-host cleanup.

All common and P1–P6 Java sources compiled with Java 15 compatibility. Run the
isolated invocation checks with Java 15+ and Python:

```sh
python btsn.common/tests/run_capability_resolver_checks.py
```

The checks cover both versioned invocation contracts, single-input and two-input
calls, equality with direct business-service results, timing enrichment,
registered infrastructure operations, an arbitrary runtime service name and a
replacement implementation selected solely by metadata. They also exercise
missing, duplicate, conflicting, unbound and unavailable implementations.
All P1–P6 placeholder classes are deliberately excluded from the test classpath,
and their absence is checked. Identical synthetic capability metadata invokes
the same implementation on each of the six host identities for both rulebase
versions. The preserved stochastic implementation is also selected and invoked
on every host identity, with its supplied identity verified in the result.
Successful physical-identity invocations therefore prove that no physical
adapter or application-specific host code is required by the invocation boundary.

The standalone checks do not execute the networked queue/fork/join workflow.
The live acceptance run remains:

`btsn.petrinet.ProjectLoader/FinancialSystem_Stage5_PriorityPreemption_BuildAndRun.xml`

After pulling, clean/rebuild `btsn.common` and the service projects in Eclipse
before running the unchanged launcher: it uses the existing `bin` directories
and does not compile Java sources. Require v001 15/15 completions with 15 forks,
15 successful joins and no orphan/incomplete workflows; require v002 40/40
completions with no incomplete workflows. Check structural and temporal reports,
Monitor/chart data and the existing priority observations. The live regression
must be repeated after the step-1 placeholder cleanup before accepting that
change. The configured application is a regression workload; it does not define
the host architecture. Work pauses after step 1 for the packaging discussion.

The frozen infrastructure check is:

```sh
git diff 4b8a2d01 -- ':(glob)**/ServiceThread.java'
```

It must produce no output.
