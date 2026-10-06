# Healthcare on generic PN hosts

Run `btsn.healthcare.ProjectLoader/Emergency_Department_BuildAndRun.xml` as an
Ant Build in Eclipse. Its default target builds the current JARs, checks `ip0`,
starts local PN hosts and Monitor, initializes databases, deploys the patient
workflow, sends ten tokens and collects measurements. Remote hosts are assumed
to have been started manually. The normal launcher output files and Monitor
database are retained.

The same architecture is used by `Federated_Radiology_BuildAndRun.xml` and
`Triple_Workflow_Emergencey_Department_Concurrent.xml` in that folder.
The concurrent launcher preserves v003 patients, v002 canaries and v001
federated requests, then collects all three versions with v999 admin tokens.

| Generic host | Logical service | Operations | Return attribute |
|---|---|---|---|
| P1 | TriageService | processTriageAssessment | triageResults |
| P2 | LaboratoryService | processLabRequest | laboratoryResults |
| P3 | CardiologyService | processCardiacAssessment | cardiologyResults |
| P4 | RadiologyService | processImagingRequest; federatedRadiologyRequest | radiologyResults |
| P5 | DiagnosisService | processClinicalDecision | diagnosisResults |
| P6 | TreatmentService | executeTreatmentPlan; executeDirectTreatment | treatmentResults |

Business implementations are in `btsn.common/src/org/btsn/business/healthcare`,
packaged exclusively into the six corresponding JARs under
`btsn.services/target/deployment/services/healthcare`. A service with several operations
still produces one JAR. The shared service support and Derby library accompany
those JARs in `target/deployment/lib`. The infrastructure JAR excludes these
implementations and their business base classes. No execution handler changed.

`Healthcare.json` defines logical contracts; `Healthcare_Infrastructure.json`
maps them to hosts and ports. `healthcare-runtime.xml` builds a separate runtime
configuration under `btsn.services/target/healthcare-runtime`; it leaves the
source Financial deployment selection intact. The healthcare token generator
accepts `-infrastructure Healthcare_Infrastructure` and `-format-service` to keep
healthcare input formats independent of physical host names. It generates
canonical bindings before deploying each workflow version.

The Diagnosis signature receives radiology, laboratory and cardiology results
in that order. Its join arcs and explicit arguments follow that order. Treatment
retains both its diagnosis input and its direct triage input. Monitor receives
collected observations outside the patient-token path; terminal transitions
finish the patient, canary and federated workflows.

The response base now uses the declared logical result attribute and lets the
host supply runtime metadata. Federated audit fields are inside
`radiologyResults` so host enrichment preserves them. Clinical assessment logic,
including the existing random triage bypass and generated clinical readings,
is preserved. No artificial processing delay was added.

For manual deployment, build each numbered place using its own `build.xml`,
and build the business JARs using `btsn.services/build.xml`. Install the selected
service JARs and their support libraries on the host classpath before starting
it. Use the prepared healthcare sibling `btsn.common` configuration (including
`BusinessServiceDefinitions/Deployment.json` and the generated healthcare
deployment facts) alongside the numbered place configuration. Invocation uses
the existing ServiceHelper in the PN JVM; these JARs do not start separate
business-service JVMs or provide remote upload/start commands.

The six old healthcare host project folders and the standalone canary launcher
have been removed. The three `ProcessTests` launchers now use generic P1,
packaged TriageService and shared Monitor/event-generator JARs as well.
See [the healthcare launcher guide](../../btsn.healthcare.ProjectLoader/README.md)
for their defaults and phase-only targets. The canary workflow definition is
retained for the concurrent launcher.

Run `btsn.services/build.xml` with `check-healthcare-services` to check the
packaged healthcare contracts, direct route and federated audit fields. Use
the migrated launcher's `analyse` target to read the usual Monitor database.

The analyser and spatial view display logical business service names. Physical
PN identities remain the keys for topology, capacity and timing calculations;
reports and spatial tooltips show them separately as orchestration locations.
Collectors include the business identity selected by the installed operation
rules, and Monitor stores it with the execution's version, operation and place.
The Gantt chart still groups complete workflows by version; its tooltips list
the business services visited by that workflow.

Diagrams also label the process definition that generated each version's
observations. Concurrent runs show a version-to-process mapping beneath the
title. The workflow elapsed/queue chart includes the names in both display
modes, PNG/PDF exports, LaTeX captions and tables, and its summary report;
tooltips show the full definition path. The measured timeline, queue comparison
and spatial view carry the same recorded process context.

Your usual `XXX_BuildAndRun.xml` captures these names automatically on the next
run. Each successful generator submission records its `-process` value in
`btsn.common.Monitor/WorkflowRunMetadata`, matched to the submitted token ID,
version and exact generation timestamp. This observation metadata does not
change token payloads, service handlers or queue scheduling. Older observations
without matching metadata show `Process not captured` and their recorded
generator source, where available; the viewer never guesses from the current
deployment or assumes a version always means the same process.

Keep `WorkflowRunMetadata` alongside `ServiceAnalysisDataBase` when archiving or
moving results. For a generator or viewer outside the checkout, both JVMs can
use `-Dbtsn.workflow.metadata.dir=<shared-directory>` to select the same metadata
location. A viewer can also point that property at archived metadata. Generated
metadata is excluded from Git and is retained across builds.

Already-collected healthcare runs can display names without rerunning: an
operation is resolved only when the available catalogues identify one logical
service. Ambiguous older operations retain their physical labels. Captured
identities take precedence and remain usable after deployment metadata changes.
Run `btsn.services/workflow-runtime.xml` with `check-service-names` for isolated
checks of these labels and preservation of the original measurement values.

Stage 3 currently groups traffic by physical service name. P4's imaging and
federated methods have different ports and queues; a P4 cross-version comparison
therefore does not establish competition for one queue. Invocation-order
candidates, including those at P1, do not prove dequeue-order violations.
Queue-priority verification remains a separate test.
