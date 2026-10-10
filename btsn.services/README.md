# Service packaging and shared build support

This is the build-support project for independent business-service JARs and the
generic host/workflow launchers. It contains no business-service implementations
or execution handlers. Implementations remain in `btsn.common`; the numbered
places remain the runtime hosts. Its default build packages the business JARs.
Host and workflow builds are optional targets.

This module builds service implementations independently of the generic P1–P6
hosts. P1–P6 `build.xml` files now import the same generic host build; handlers
and `ServiceThread` are unchanged.
Service identity and host placement remain separate: none of the generated service
JARs belongs to a numbered host.

## Organisation

| Location | Responsibility |
|---|---|
| `build/packaging/` | Catalogue inventory and independent service packaging |
| `build/hosts/` | Shared infrastructure, generic host builds and portable place releases |
| `build/workflows/` | Monitor/generator builds, local channel checks and runtime preparation |
| `build/checks/` | Packaged-service, host-boundary and analysis regression targets |
| `deployments/healthcare/` | Healthcare deployment profile and channel facts |
| `deployments/models/` | Deterministic, stochastic loop, fork, double-join and traffic-light deployment profiles |
| `catalogues/` | Supplemental packaging inventory |
| `src/`, `tests/` | Java build helpers and regression checks |
| `docs/` | Healthcare, model and portable-release guides |

The root XML files are stable Ant entry points. Existing Eclipse configurations
and launcher imports keep their paths and target names. Use these entry points;
XML files under `build/` are implementation fragments imported by them.
`packaging.json` remains the inventory configuration entry point. All generated
outputs stay under the existing `target` directories.

## Build and check

From the repository root, with Ant 1.10.2+ running on a JDK 15+:

```sh
ant -f btsn.services/build.xml clean check
```

Use `ant -f btsn.services/build.xml package` to build without running the checks.
In Eclipse, run `btsn.services/build.xml` as an Ant Build and select `check` or
`package`. The Ant JVM must be a JDK, so the Java compiler is available.
No Python, Maven, Ivy, shell script or downloaded dependency is required.

To build and check the actual host JARs and their service classpaths:

```sh
ant -f btsn.services/build.xml check-hosts
```

This builds all six hosts, shared infrastructure, Monitor and event generators.
Each host passes the existing 108 invocation checks with its own packaged
`ServiceHelper`, and the origin check verifies that every implementation and
shared service helper loads exactly once from the deployed service JARs.

After pulling the branch, import the new project into your existing Eclipse
workspace: **File → Import → General → Existing Projects into Workspace**.
Select the repository root, select `btsn.services`, and leave **Copy projects into
workspace** unchecked. Keep the existing `btsn.common` project imported; the
packaging reader uses its JSON library. Expand `btsn.services`, right-click its
`build.xml`, and choose **Run As → Ant Build…** to select `clean` and `check`.

The output is `btsn.services/target/service-deployment.zip`, containing:

| Location | Contents |
| --- | --- |
| `services/healthcare/<service>.jar` | Six healthcare implementations |
| `services/financial/<service>.jar` | Seven Financial implementations |
| `services/models/<service>.jar` | Seven deterministic implementations, the preserved stochastic implementation and eight stochastic workflow adapters |
| `lib/service-support.jar` | Shared service helpers discovered through compilation |
| `lib/*.jar` | Declared third-party runtime dependencies, copied without alteration |
| `catalogues/*.json` | Packaging catalogue snapshots |
| `deployment-index.json` | Implementation classes, operations, input/return contracts, JAR paths and dependencies |
| `SHA256SUMS` | Bundle file checksums |

`packaging.json` selects source roots, catalogues, runtime libraries and Java
release. Its `catalogueDomains` map assigns catalogue filenames to deployment
folders. Without an override, the catalogue's `domain` is used in lowercase.
Domain names must contain only letters, digits, underscores or hyphens and start
with a letter. Several catalogues may share a domain; service identities remain
unique across the complete inventory. Each indexed operation records its domain
and `services/<domain>/<service>.jar` path. This grouping does not select a host,
activate a service or change its invocation contract.

Service manifests resolve the shared `lib/` directory from two levels below it.
Keep the domain folders when deploying selected JARs. For a manual Java
classpath, use an explicit JAR path or `services/healthcare/*`,
`services/financial/*` or `services/models/*`. Java's `services/*` wildcard does
not search these subdirectories. The Ant build and development launchers collect
service JARs recursively and retain their existing deployment selection.

The builder reads every catalogue entry, including preserved unbound services, without domain-specific business logic or host-placement decisions. The separate stochastic inventory is packaging data; it does not
change active deployment metadata. The current inventory yields twenty-nine service
JARs: seven Financial implementations, seven deterministic model implementations,
six healthcare implementations, the preserved stochastic implementation and eight
stochastic workflow adapters.
See [PETRINET_MODELS.md](docs/PETRINET_MODELS.md)
for the two-place and six-place double-join models using actual platform measurements.
Use `-Dpackaging.config=/path/to/packaging.json` for another inventory.

Ant compiles the small Java inventory reader using the existing repository JSON
library. The reader generates `target/service-tasks.xml`, with explicit native
Ant tasks for every catalogue entry. Ant executes that generated build to compile
selected implementations with `javac`, create their JARs with `jar`, copy runtime
libraries with `copy`, and create the deployment bundle with `zip`. The reader
handles metadata, dependency-boundary validation and checksums; it does not invoke
an external build tool or create the archives itself.

Compilation starts with only the selected implementation sources and declared
libraries. Java follows their source dependencies; existing `bin` directories and
host classes are never inputs. Each implementation is separated from shared
helpers, so installing several services does not duplicate their helper classes.
Host handlers, invocation resolver and place adapters are rejected if compilation
pulls them into the service dependency closure. Current implementations use only
compile-time source dependencies; future reflection-only helpers or resources
will need explicit packaging support. Archive timestamps are fixed. Repeat builds
produce the same output for the same sources, compiler, Ant version, timezone and
dependency JARs.

The shared `host-build.xml` builds each host against packaged services and common
infrastructure. `infrastructure.xml` excludes service-owned implementation and
helper sources using the compiler-discovered source list. Eclipse `bin` outputs
are left untouched and are absent from the new launch classpaths. Common runtime
libraries already supplied by the service bundle are excluded from the old
library path to avoid duplicate copies.

## Numbered-place releases and BuildAndRun

Run each numbered place's `build.xml` in Eclipse to build that place's executable
JAR and portable ZIP. See [PLACE_RELEASES.md](docs/PLACE_RELEASES.md) for outputs and
manual startup commands. These packages contain infrastructure only.

Run the service project's `build.xml` in Eclipse to build the separate business
implementation JARs. Its default target is `package`.

The Stage-5 BuildAndRun continues to build the development runtime, launch its
configured local places and Monitor, deploy rules and generate tokens. Business
implementations load from their separate JARs through the existing ServiceHelper;
this remains the existing in-process invocation, not a separate business-service
program. Remote runtimes must be started manually. Portable packaging does not
replace this development workflow.

All domains select the same `InfrastructureDefinitionFolder/SingleHost.json`
and separate placements from `ServiceDeploymentFolder`. Runtime preparation
generates their network RuleML before building the master service rules.
`-Dhost.address=127.0.0.1` applies a synchronized local address override to the
isolated runtime. Changing service functions preserves the fixed node ports.

All PN BuildAndRun launchers now default to `auto`: the build reads the active
`boundChannel(ip0, address)` fact from
`btsn.common/RuleBase/Generated/InfrastructureDeployment.ruleml.xml` and compares
it with the machine's network interfaces. Loopback or an address assigned to
this machine starts the existing local runtime; any other address skips startup
and assumes that the remote runtime was started manually. It does not send a
remote start request. `p1.mode` through `p6.mode` and `monitor.mode` still accept
explicit `local` or `remote` overrides where those components occur in a workflow.
The build prints the address and each component's launch decision. Missing or
conflicting ip0 facts fail the build rather than guessing a destination.

### Generated launchers

ProcessEditor's **Build and Run** button creates a launcher for a saved, deployed
process. The launcher holds only what differs between processes (process,
profile, event generator, target place, version, token count and schedule, and
the hosts the deployment uses) and imports `process-runtime.xml`, which holds the
shared phases: initialise, deploy and fire tokens, collect. The button also writes
the token schedule under `btsn.common.eventgenerators/EventTriggeringFile`, a
deployment profile under `deployments/` when none selects the service deployment
yet, and an initialiser/collector pair under
`btsn.common/ProcessDefinitionFolder/common` for a host set that has none.
[`tests/launchers/P1_Tutorial_Generated_BuildAndRun.xml`](tests/launchers/P1_Tutorial_Generated_BuildAndRun.xml)
is a generated example; `check-launchers` prepares it with the other launchers.
Generated launchers cover one Petri-net or financial process per run; the
hand-written launchers remain for multi-process and healthcare runs.

`auto-deployment.xml` is a Java/Ant build helper, independent of business
catalogues. Run its default check target to verify address and rule parsing.
Only the launcher decision changes; handlers, token scheduling, service
invocation and process deployment keep their existing behaviour. The healthcare
launchers also use this check; see [HEALTHCARE.md](docs/HEALTHCARE.md).

The obsolete healthcare-specific host projects and standalone canary launcher
have been removed. `btsn.healthcare.ProjectLoader` remains the active healthcare
launcher project. Clinical implementations, workflow definitions and the shared
Monitor/event-generator projects are retained.

All retained model and financial launchers and their standalone utility phases
now use the packaged runtime. Run the usual XML directly; it builds its JARs
automatically. Financial examples live in
[btsn.financial.ProjectLoader](../btsn.financial.ProjectLoader/README.md); model
examples and utility phases remain in
[btsn.petrinet.ProjectLoader](../btsn.petrinet.ProjectLoader/README.md). See those
guides for isolated deployment profiles, stochastic examples and phase directories.

For existing Eclipse workspaces, [the runtime Git cleanup guide](docs/RUNTIME_GIT_CLEANUP.md)
describes the one-time verified backup before removing historical databases,
installed rules and chart exports from Git. `Finish_RuntimeCleanup.xml` prepares
and restores that backup; ordinary workflow launchers remain the entry points
afterwards.
