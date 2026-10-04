# Independent service deployment JARs

This module builds service implementations independently of the generic P1–P6
hosts. Existing host `build.xml` files, handlers and `ServiceThread` are unchanged.
Service identity and host placement remain separate: none of the generated service
JARs belongs to a numbered host.

From the repository root, with Python 3.9+ and a JDK 15+:

```sh
python3 btsn.services/build_services.py
python3 btsn.services/tests/check_packaged_services.py
python3 btsn.common/tests/run_capability_resolver_checks.py --service-bundle btsn.services/target/deployment
```

Alternatively, `ant -f btsn.services/build.xml check` builds and runs both checks.
Override the Python executable with `-Dpython=python` if needed. The Ant wrapper
delegates to the same builder; Ant itself is not needed for the Python commands.

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
Use `--config /path/to/packaging.json` for another inventory.

Compilation starts with only the selected implementation sources and declared
libraries. Java follows their source dependencies; existing `bin` directories and
host classes are never inputs. Each implementation is separated from shared
helpers, so installing several services does not duplicate their helper classes.
Host handlers, invocation resolver and place adapters are rejected if compilation
pulls them into the service dependency closure. Current implementations use only
compile-time source dependencies; future reflection-only helpers or resources
will need explicit packaging support. Class and archive output is reproducible
for the same sources, compiler and dependency JARs.

To deploy, extract the bundle and add the selected service JAR to a generic host
JVM's classpath. Keep `services/` and `lib/` beside each other: the JAR manifest
lists its support libraries relative to that location. Alternatively include
`services/*` and `lib/*` explicitly, using `:` on Unix or `;` on Windows. Every
host uses the same mechanism. The host still supplies its infrastructure classes,
logging configuration, resolver metadata and installed rules separately.

Catalogue status is informational for packaging. Activating or placing a service
still requires the existing deployment configuration and rule contracts. Copying
a JAR does not activate it, and the bundle is not an executable host JAR.

The implementation sources remain in `btsn.common/src` during this packaging step.
Before switching a running host to these JARs, remove duplicate implementation
and service helper classes from its old classpath (including common `bin` if it
contains them). Classpath order must not decide which implementation runs. Host
build/launcher refactoring is the next integration step; this module does not
change those launchers or perform a live deployment.

Validation loads each implementation in a fresh JVM with only its own JAR and
manifest dependencies, invokes its declared operation and checks other service
implementations are absent. The resolver regression separately invokes packaged
implementations across all six host identities, with no place adapters or service
source classes on its classpath.
