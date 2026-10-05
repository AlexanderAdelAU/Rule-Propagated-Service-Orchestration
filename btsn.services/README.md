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
change active deployment metadata. The current inventory yields eight service
JARs: seven catalogue implementations and the preserved stochastic implementation.
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

## Manual two-JAR distribution

Build from the service project:

```sh
ant -f btsn.services/build.xml distribution
```

Or use the existing numbered project's release build, for example:

```sh
ant -f btsn.petrinet.places.p1/build.xml release
ant -f btsn.petrinet.places.p2/build.xml release
```

Every numbered project imports the same build. Its release target creates
`target/btsn-services.zip`. The service project also writes that ZIP to
`btsn.services/target/btsn-services.zip`. The service project's default target is
now `distribution`.

Copy the ZIP to the remote machine and unzip it. It contains exactly two JAR files:

| File | Purpose |
| --- | --- |
| `btsn-business-services.jar` | All packaged business implementations and their shared service helpers; starts the selected place worker |
| `btsn-infrastructure.jar` | Existing generic handlers, shared infrastructure, third-party libraries and Monitor runtime; starts initialization/collection or Monitor services |

Both are executable. Keep them together and run, from the unzipped directory:

```sh
java -jar btsn-infrastructure.jar p1
java -jar btsn-business-services.jar p1
```

Run those two commands in separate terminals. Alternatively, `launch.bat p1`
opens both on Windows; `sh launch.sh p1` starts both on Linux/macOS. Substitute
`p2` through `p6` for the selected place. `launch.bat monitor` or
`sh launch.sh monitor` starts Monitor from the infrastructure JAR. Separate
`launch-business` and `launch-infrastructure` scripts are also included.
Each command accepts an optional startup rule version, such as `p1 v001`.
The destination needs Java 15+ and a writable unzipped directory; it needs neither
Ant nor the source repository.

The startup commands call the unchanged `ServiceLoader`. Business startup selects
the place's existing worker; infrastructure startup selects initialization and
collection services. `ServiceThread` and `ServiceHelper` are unchanged. Business
implementation objects are instantiated through the existing helper as tokens
arrive, inside the worker started by the business command.

Runtime libraries and the existing component runtimes are embedded in the
infrastructure JAR and unpacked locally on first startup. This preserves each
component's original handlers, including Monitor, without merging different
classes with the same Java name. Business implementations are combined into the
business JAR and are not embedded in the infrastructure runtime. The ZIP also
contains configuration files and startup commands, following the original P1/P2
release ZIP approach. There is no receiver or remote JAR upload/start mechanism.

Initial deployment definitions and rules are under `config`. Configure their
channel addresses for the destination before first startup, and use the same
mapping in the development project. Runtime working directories and databases
are under `.run/business` and `.run/infrastructure`; received process rules and
data are preserved between starts. Editing the initial config after first start
does not overwrite these existing runtime directories; stop services and update
their working configuration when changing deployment settings.

Wait for the existing launcher and service threads to finish startup. Operations
must be declared by the existing `activeService` deployment rules. A place with
no bound business operation does not acquire one merely because its JAR is present.
All six places use identical startup logic and configuration rules.

Then run the normal business-process deployment Ant build. The Stage-5 launcher
now deploys rules and sends initialization, workflow and collector tokens to the
already running services. It does not start, stop or restart those services.
Its workflow definitions, token counts, timings and rule-deployment mechanism
remain unchanged. Service output is in the consoles on the machine running them.

Validate the copied ZIP's startup and invocation boundaries with:

```sh
ant -f btsn.services/build.xml check-distribution
```

The test extracts the ZIP outside the repository to a directory with spaces,
starts the executable JARs, checks business/infrastructure worker separation and
Monitor startup, and runs the existing 108 invocation checks for every numbered
place using only the copied artifacts. These are local isolated-process checks;
actual LAN process deployment must be validated with the destination addresses.
