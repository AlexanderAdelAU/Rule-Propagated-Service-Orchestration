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

Runtime changes are limited to the new common resolver and identical small
changes in the six P1–P6 `ServiceHelper.java` copies. Every `ServiceThread.java`,
all other existing infrastructure handlers, Monitor, business implementations,
physical adapters, workflow definitions, existing deployment definitions and the
Stage-5 launcher remain byte-for-byte unchanged from the recovery baseline.

All common and P1–P6 Java sources compiled with Java 15 compatibility. Run the
isolated invocation checks with Java 15+ and Python:

```sh
python btsn.common/tests/run_capability_resolver_checks.py
```

The checks cover both versioned invocation contracts, single-input and two-input
calls, equality with existing adapter business results, timing enrichment,
registered infrastructure operations, an arbitrary runtime service name and a
replacement implementation selected solely by metadata. They also exercise
missing, duplicate, conflicting, unbound and unavailable implementations.

The standalone checks do not execute the networked queue/fork/join workflow.
The live acceptance run remains:

`btsn.petrinet.ProjectLoader/FinancialSystem_Stage5_PriorityPreemption_BuildAndRun.xml`

After pulling, clean/rebuild `btsn.common` and the service projects in Eclipse
before running the unchanged launcher: it uses the existing `bin` directories
and does not compile Java sources. Require v001 15/15 completions with 15 forks,
15 successful joins and no orphan/incomplete workflows; require v002 40/40
completions with no incomplete workflows. Check structural and temporal reports,
Monitor/chart data and the existing priority observations. Do not remove the
transitional adapters or merge this candidate until that regression passes.

The frozen infrastructure check is:

```sh
git diff 4b8a2d01 -- ':(glob)**/ServiceThread.java'
```

It must produce no output.
