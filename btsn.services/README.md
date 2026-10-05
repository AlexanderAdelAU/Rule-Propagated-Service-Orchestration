# Independent service deployment JARs

This module builds service implementations independently of the generic P1–P6
hosts. P1–P6 `build.xml` files now import the same generic host build; handlers
and `ServiceThread` are unchanged.
Service identity and host placement remain separate: none of the generated service
JARs belongs to a numbered host.

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
| `services/<service>.jar` | One implementation and its nested classes |
| `lib/service-support.jar` | Shared service helpers discovered through compilation |
| `lib/*.jar` | Declared third-party runtime dependencies, copied without alteration |
| `catalogues/*.json` | Packaging catalogue snapshots |
| `deployment-index.json` | Implementation classes, operations, input/return contracts, JAR paths and dependencies |
| `SHA256SUMS` | Bundle file checksums |

`packaging.json` selects source roots, catalogues, runtime libraries and Java
release. The builder reads every catalogue entry, including preserved unbound
services, without interpreting the business domain or making host-placement
decisions. The separate stochastic inventory is packaging data; it does not
change active deployment metadata. The current inventory yields ten service
JARs: seven Financial implementations, two deterministic model implementations
and the preserved stochastic implementation. See [PETRINET_MODELS.md](PETRINET_MODELS.md)
for the two-place model using actual platform measurements.
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
JAR and portable ZIP. See [PLACE_RELEASES.md](PLACE_RELEASES.md) for outputs and
manual startup commands. These packages contain infrastructure only.

Run the service project's `build.xml` in Eclipse to build the separate business
implementation JARs. Its default target is `package`.

The Stage-5 BuildAndRun continues to build the development runtime, launch its
configured local places and Monitor, deploy rules and generate tokens. Business
implementations load from their separate JARs through the existing ServiceHelper;
this remains the existing in-process invocation, not a separate business-service
program. Remote runtimes must be started manually. Portable packaging does not
replace this development workflow.

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

`auto-deployment.xml` is a Java/Ant build helper, independent of business
catalogues. Run its default check target to verify address and rule parsing.
Only the launcher decision changes; handlers, token scheduling, service
invocation and process deployment keep their existing behaviour. Healthcare
launchers have not been migrated to this check.

The abandoned aggregate two-JAR distribution and its launch commands have been
removed. Active infrastructure handlers and healthcare projects are preserved.
