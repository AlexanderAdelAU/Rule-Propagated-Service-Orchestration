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

Each host JAR is created at `btsn.petrinet.places.pN/target/btsn.petrinet.places.pN.jar`.
Its manifest points to the shared infrastructure, service JARs and libraries in
the existing repository layout. The `release` target builds these artifacts;
it does not create a standalone host distribution ZIP.

The Stage-5 launcher builds the packaged runtime and generates the rules, uploads
a self-contained package to each configured receiver, and sends an explicit start
request before sending initialization or workflow tokens. It does not launch the
receiver. The workflow definitions, routing, timings and token counts are unchanged.

## Remote deployment and startup

Build the receiver and the deployable package with Ant:

```sh
ant -f btsn.services/build.xml remote-bundle
```

The outputs are `target/btsn-deployment-host.jar` (the independent receiver) and
`target/remote-service-deployment.zip` (service runtime JARs, business JARs,
libraries, metadata, loader queries and rules). No source files or Eclipse
`bin` directories are needed at the destination.

Install the receiver JAR on each destination once and run it independently of Ant:

```sh
java -jar btsn-deployment-host.jar 0.0.0.0 41000 /srv/btsn/deployments YOUR_TOKEN
```

Use an appropriate local directory on Windows instead of `/srv/btsn/deployments`.
The receiver waits for deployment requests and remains running when an Ant run
finishes. Ant does not start, stop or restart that receiver. If your currently
running host software has no deployment endpoint, installing this receiver is
required; a build cannot add an endpoint to an existing JVM remotely.

Deploy and start P1 using that already running receiver:

```sh
ant -f btsn.services/build.xml deploy-start -Ddeployment.host=DESTINATION_IP -Ddeployment.port=41000 -Ddeployment.token=YOUR_TOKEN -Ddeployment.component=p1 -Ddeployment.version=v001
```

Use `p2` through `p6`, or `monitor`, with the same command. The numbered project's
`run` target also delegates to this deployment mechanism; supply the same host,
port and token properties.

The receiver checks the upload checksum, stores and extracts the received package,
then starts a **service JVM** from those received JARs. That JVM calls the unchanged
`ServiceLoader`, which constructs the existing rule handlers and `ServiceThread`
workers. Business implementations execute through the unchanged `ServiceHelper`
when tokens arrive. Business implementation classes are not themselves Java threads.
The independently running receiver is never relaunched by a deployment request.

Startup is acknowledged only after the launcher and its workers report successful
startup. Upload, authentication or startup failures fail the Ant run before tokens
are sent. Repeating a start request for the same running component/version/package
returns `ALREADY RUNNING`; it does not start another copy. A request to replace a
running component with a different package/version is rejected; it does not silently
stop or replace that service. Receiver logs and service output files are stored in
the receiver's deployment directory, not the development repository.

For Stage 5, put the following properties in
`btsn.petrinet.ProjectLoader/deployment.properties` (or supply them with `-D`):

```properties
deployment.token=YOUR_TOKEN
deployment.port=41000
p1.deployment.host=DESTINATION_IP
p2.deployment.host=DESTINATION_IP
p3.deployment.host=DESTINATION_IP
p4.deployment.host=DESTINATION_IP
p5.deployment.host=DESTINATION_IP
monitor.deployment.host=DESTINATION_IP
```

Configure the existing infrastructure definition's channel addresses for the actual
service destinations as well: the receiver address controls package transfer, while
workflow channel addresses control token and rule delivery. Those are distinct
settings. Stage 5 deploys P1-P5 and Monitor; P6 uses the same deployment mechanism.

The implementation sources remain in `btsn.common/src`; the uploaded package
contains compiled JARs only. All business identity and placement comes from the
existing deployment metadata and rules, not receiver code.

Validate real upload and service startup with:

```sh
ant -f btsn.services/build.xml check-remote
```

This launches a test receiver JVM containing only the receiver JAR, transfers the
package over a socket, starts all six places and Monitor from received files,
checks duplicate starts and rejected uploads, and runs the existing 108 invocation
checks for each place against the received JARs. It also checks class origins to
exclude accidental use of development classes. The test stops only its own test
receiver and services at completion. It does not constitute a test across real LAN
machines; run Stage 5 with the actual destination addresses for that validation.
