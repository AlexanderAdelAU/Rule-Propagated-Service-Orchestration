# Separate P1–P6 releases

Each numbered project's `build.xml` builds only that place. Run its default
target or select `release` (the `jar` target produces the same portable release):

```sh
ant -f btsn.petrinet.places.p1/build.xml release
ant -f btsn.petrinet.places.p2/build.xml release
ant -f btsn.petrinet.places.p3/build.xml release
ant -f btsn.petrinet.places.p4/build.xml release
ant -f btsn.petrinet.places.p5/build.xml release
ant -f btsn.petrinet.places.p6/build.xml release
```

For P1 the outputs are:

- Executable JAR: `btsn.petrinet.places.p1/target/release/btsn.petrinet.places.p1/btsn.petrinet.places.p1.jar`
- Portable ZIP: `btsn.petrinet.places.p1/target/btsn.petrinet.places.p1.zip`

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
