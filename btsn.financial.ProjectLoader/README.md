# Financial system launchers

Import `btsn.financial.ProjectLoader` into Eclipse using **File → Import →
General → Existing Projects into Workspace**. Select the repository root and
leave **Copy projects into workspace** unchecked. This project contains Ant
entry points; it does not need a Java source folder or duplicate runtime libraries.

Run the required XML directly as an **Ant Build**, with Ant 1.10.2+ and a JDK 15+.
Each launcher automatically builds the packaged business services, shared
infrastructure, generic numbered hosts, Monitor and event generators. Its default
target remains `run-complete-workflow`.

| Launcher | Purpose |
|---|---|
| `FinancialSystem_P1_Simple_BuildAndRun.xml` | ValidationService on P1 |
| `FinancialSystem_P1_P5_BuildAndRun.xml` | Full Financial workflow with validation, credit/fraud checks, underwriting join and decision |
| `FinancialSystem_Stage3_Concurrent_BuildAndRun.xml` | Concurrent full and pre-screen workflows |
| `FinancialSystem_Stage4_LiveDeployment_BuildAndRun.xml` | Live-deployment workflow example |
| `FinancialSystem_Stage5_PriorityPreemption_BuildAndRun.xml` | Foreground/background priority workflow example |

These five files were moved unchanged from `btsn.petrinet.ProjectLoader`.
Existing Eclipse Ant launch configurations referencing their former paths must
select the XML in this project or be recreated by running the moved file.
Petri-net model launchers remain in [their own project](../btsn.petrinet.ProjectLoader/README.md).

The Financial deployment profile remains under
`btsn.services/deployments/financial`. Business implementations, canonical
contracts and process definitions remain in `btsn.common`, including the current
`ProcessDefinitionFolder/petrinet` administration/workflow paths. Moving the
launcher project does not rename those process identifiers.

Each launcher keeps its isolated host configuration and databases under
`btsn.services/target/launchers/<Ant-project-name>`, so existing prepared host
state uses the same location. Monitor retains its usual working directory and
results. Service output files now appear beside the launch XML in this project;
their filenames, token counts, versions, delays and phase order are unchanged.
Local/remote startup selection and overrides remain available. Remote hosts must
already be running with matching prepared configuration and deployed JARs.

Run `prepare-runtime` for preparation only or `analyse` for the existing Monitor
analysis. Developers can check all Financial and model entry points using
`btsn.services/build.xml` target `check-launchers`; this configures the real XML,
checks process contracts and prepares isolated runtimes without starting services.

See [the Financial application guide](../btsn.common/FinancialApplication.md)
for business contracts and regression expectations.
