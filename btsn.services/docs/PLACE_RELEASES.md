# Host release

> **Running a host on another machine as part of a run?** Use the launcher's
> `host-packages` target instead (see
> [Run on several machines](../../Reference.md#7-run-on-several-machines)). Its
> packages carry the master ruleset generated for that deployment and the host's
> business services. The release below carries the source rules and no
> business services, so it only checks that a host starts on its own.

Every node runs the same host program, `btsn.rpso.host`. It used to be six
numbered projects, `btsn.rpso.places.p1` to `p6`, identical apart from their names.
The node a host runs as is given when it starts (`-node P3`), so a node added to
the infrastructure (P7, P8, ...) needs no project of its own.

After pulling this into an existing Eclipse workspace:

- delete the `btsn.rpso.places.p1` to `p6` project entries (the folders are gone
  from the repository; any left on disk hold only old run output);
- **File → Import → General → Existing Projects into Workspace**, choose
  `btsn.rpso.host`, leave **Copy projects into workspace** unchecked.

Build the release with the project's default target (`jar` and `release` do the same):

```sh
ant -f btsn.rpso.host/build.xml
```

- Executable JAR: `btsn.rpso.host/target/release/btsn.rpso.host/btsn.rpso.host.jar`
- Portable ZIP: `btsn.rpso.host/target/btsn.rpso.host.zip`

The release holds the host classes, generic infrastructure, runtime libraries,
rule and configuration files and launch commands. Business implementations and
their business/simulation base classes are excluded.

Copy the ZIP to the destination, extract the **entire ZIP** into a writable folder
and start it as a node: `launch.bat P3` on Windows or `sh launch.sh P3` on
Linux/macOS. The script runs the JAR with `-version v001 -node P3`; further
arguments go to the `ServiceLoader`. Keep the libraries, configuration and
sibling `btsn.common` directory with the JAR. The destination needs Java 15+,
not Ant or the source checkout. Set the channel/address mappings for the
destination before startup.

`check-release` extracts the ZIP outside the repository into a directory with
spaces, checks the manifest and dependency paths, rejects business classes, and
starts the JAR as P1 and as P2 to verify their service and rule-handler threads.
