# Separate P1–P6 releases

The six host projects are now named `btsn.rpso.places.p1` through
`btsn.rpso.places.p6`. Their executable JARs and release ZIPs use those names as
well. Physical orchestration identities (`P1_Place` through `P6_Place`), Java
packages, handlers, network ports and rule semantics are unchanged.

After pulling the rename into an existing Eclipse workspace, remove the old
`btsn.petrinet.places.p1` through `p6` project entries without deleting files
from disk. Use **File → Import → General → Existing Projects into Workspace**
to import the six renamed folders; leave **Copy projects into workspace**
unchecked. Remove obsolete closed healthcare host entries in the same way.
Keep `btsn.healthcare.ProjectLoader`, which is still the active healthcare
launcher project. Run your usual BuildAndRun XML after refreshing the workspace;
it builds the renamed JARs automatically.

Each numbered project's `build.xml` builds only that place. Run its default
target or select `release` (the `jar` target produces the same portable release):

```sh
ant -f btsn.rpso.places.p1/build.xml release
ant -f btsn.rpso.places.p2/build.xml release
ant -f btsn.rpso.places.p3/build.xml release
ant -f btsn.rpso.places.p4/build.xml release
ant -f btsn.rpso.places.p5/build.xml release
ant -f btsn.rpso.places.p6/build.xml release
```

For P1 the outputs are:

- Executable JAR: `btsn.rpso.places.p1/target/release/btsn.rpso.places.p1/btsn.rpso.places.p1.jar`
- Portable ZIP: `btsn.rpso.places.p1/target/btsn.rpso.places.p1.zip`

P2–P6 use the corresponding project and filenames. Builds compile shared
infrastructure into the selected place's own output; they do not invoke another
place's build or a business-service build. A release contains the selected place's
classes, generic infrastructure, runtime libraries, rule/configuration files and
its own launch commands. Business implementations and their business/simulation
base classes are excluded. Existing handlers are unchanged.

Copy that place's ZIP to the destination and extract the **entire ZIP** into a
writable folder. Run `launch.bat` on Windows or `sh launch.sh` on Linux/macOS.
The launch command selects the correct working directory and runs that place's
executable JAR with `-version v001`. Arguments supplied to the script are passed
to the existing loader. Keep the libraries, configuration and sibling
`btsn.common` directory with the executable JAR. The destination requires Java
15+; it does not require Ant or the source checkout.

Set the existing channel/address mappings for the destination before startup.
The service and rule-handler threads start through the unchanged `ServiceLoader`.
Each ZIP starts one numbered place, never the other numbered places. Business
service packaging and the separate business-service invocation boundary are not
implemented by this place-build step.

To check a release, use its own `check-release` target. It extracts that ZIP outside
the repository into a directory with spaces, checks the manifest and dependency
paths, rejects business or other-place classes, and starts the executable JAR to
verify service and rule-handler threads without business implementation JARs.
