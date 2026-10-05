# Healthcare build and run definitions

All retained Ant launchers use generic numbered hosts, independently packaged
business-service JARs, the shared infrastructure JAR, Monitor and common event
generators. They build through the existing `btsn.services` tooling. No legacy
healthcare place project or Eclipse `bin` output is on their classpaths.

Run the selected XML file as an **Ant Build** in Eclipse with Ant 1.10.2+ and a
JDK 15+. Paths resolve from the build file's directory, including `ProcessTests`.

| Build file | Default behaviour |
|---|---|
| `Emergency_Department_BuildAndRun.xml` | Initialize P1–P6 and Monitor, run ten patients, collect observations |
| `Federated_Radiology_BuildAndRun.xml` | Initialize P4 and Monitor, run federated requests, collect observations |
| `Triple_Workflow_Emergencey_Department_Concurrent.xml` | Run the patient, canary and federated workflows concurrently and collect all three versions |
| `ProcessTests/Triage_Initializer.xml` | Start configured P1 and Monitor components and initialize their databases |
| `ProcessTests/Triage_Workflow.xml` | Start configured P1 and Monitor components, execute ten triage requests and collect observations; initialization is optional |
| `ProcessTests/Triage_Collector.xml` | Start configured P1 and Monitor components and collect existing v001, v002 and v003 measurements |

The standalone `Triage_CanaryTest_BuildAndRun.xml` has been removed. The concurrent
application retains its canary workload and its existing JSON definition.

## Triage process tests

The three process-test launchers import `ProcessTests/triage-runtime.xml` to share
the current runtime configuration, packaged classpaths and phase commands.
`Triage_Workflow.json` contains a logical TriageService operation followed by a
terminal transition. `Healthcare_Infrastructure.json` places that operation on
P1. Monitor receives collector observations separately from the patient path.
Initialization and collection use P1's generic infrastructure adapters and v999
administration tokens. Workflow and collection operations have separate settings.

| Build file | Target for already running components |
|---|---|
| `Triage_Initializer.xml` | `trigger-all-initializers-sequential` |
| `Triage_Workflow.xml` | `phase1-init-only`, `phase2-workflow-only`, `phase3-collect-only` |
| `Triage_Collector.xml` | `performance-collector-trigger` |

For a clean triage run, select **`run-with-initialization`** in
`ProcessTests/Triage_Workflow.xml`. It starts the configured components once,
initializes databases, executes triage and collects measurements. Its default
`run-complete-workflow` target retains the previous optional-initialization behaviour.
Use `validate-environment` to build and check the runtime without sending events,
`analyse` after collection to analyse the Monitor database, and `help` for options.

The default launch mode is `auto`: a component starts locally when the configured
`ip0` address belongs to the current machine. Override with `triage.mode` and
`monitor.mode` set to `local` or `remote`. Remote components must already be
running. Phase-only targets always use existing components. Locally launched
components remain running after the phases finish; stop the Ant run before
starting another launcher on the same ports. The obsolete direct-to-bin compile,
blocking sequential-start and global Java-kill targets have been removed.

Typical workflow options are `rule.version`, `token.count`, `target.operation`,
`patient.id` and `provider.id`. Collector options are `query.version` (one version
or a comma-separated list) and `collector.operation`. `init.version` and
`collector.version` default to v999; `rule.version` defaults to v001.

The launcher changes leave execution handlers, scheduling and business logic
unchanged. Shared `btsn.services` build organisation remains a separate cleanup.
