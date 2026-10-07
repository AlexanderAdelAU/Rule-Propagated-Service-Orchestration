# Financial Application — Authoritative Artifacts

This document identifies the authoritative Financial application artifacts for the
`invocation-boundary-resolver` branch, which starts from the verified
`4b8a2d01` recovery baseline. It is descriptive only; no runtime component reads
this file.

## Active business workflows

The Financial application currently has two active workflow definitions:

- `ProcessDefinitionFolder/petrinet/Workflow/FinancialSystem_P1_P5_Workflow.json`
  - v001 full loan application workflow
  - Validation -> CreditCheck/FraudCheck -> Underwriting -> Decision
  - business terminates directly; Monitor is not part of the business route
- `ProcessDefinitionFolder/petrinet/Workflow/FinancialSystem_Stage3_PreScreen_Workflow.json`
  - v002 pre-screen workflow
  - Validation -> CreditCheck -> terminate
  - shares physical P1/P2 infrastructure with v001

These workflow definitions contain logical business-service identities only.

## Infrastructure definition

Authoritative deployment mapping:

- `InfrastructureDefinitionFolder/financial/FinancialSystem_Infrastructure.json`

Current logical-to-physical mapping:

| Business capability | Physical host |
| --- | --- |
| ValidationService.processToken | P1 |
| CreditCheckService.processToken | P2 |
| FraudCheckService.processToken | P3 |
| UnderwritingService.processToken | P4 |
| DecisionService.processToken | P5 |

Physical placement is a deployment concern and is intentionally separate from the
workflow definitions and business implementation.

## Business capability catalog

Authoritative capability inventory:

- `BusinessServiceDefinitions/FinancialSystem.json`

Active implementations:

- `org.btsn.business.financial.ValidationService`
- `org.btsn.business.financial.CreditCheckService`
- `org.btsn.business.financial.FraudCheckService`
- `org.btsn.business.financial.UnderwritingService`
- `org.btsn.business.financial.DecisionService`

Preserved but currently unbound capabilities:

- `org.btsn.business.financial.IdentityVerificationService`
- `org.btsn.business.financial.AffordabilityAssessmentService`

The preserved-unbound capabilities are retained for traceability and future use,
but they are not part of the current v001/v002 Financial workflows.

## Canonical bindings

Authoritative logical service contracts:

- `ServiceAttributeBindings/ValidationService/ValidationService-CanonicalBindings.ruleml.xml`
- `ServiceAttributeBindings/CreditCheckService/CreditCheckService-CanonicalBindings.ruleml.xml`
- `ServiceAttributeBindings/FraudCheckService/FraudCheckService-CanonicalBindings.ruleml.xml`
- `ServiceAttributeBindings/UnderwritingService/UnderwritingService-CanonicalBindings.ruleml.xml`
- `ServiceAttributeBindings/DecisionService/DecisionService-CanonicalBindings.ruleml.xml`

Canonical contracts are attached to logical business services, not physical P-node
identities.

## Current physical host placeholders

All P1-P6 place classes are retained physical host placeholders with their
existing constructor signatures. Concrete service dependencies, operation
methods, delegates, imports and service-base inheritance have been removed.
The names remain available as deployment/routing identities. This host contract
is independent of this application's selected capabilities.

The generic `ServiceHelper` invocation boundary resolves their configured runtime
operations directly to the logical Financial business-service implementations.
Its metadata selection is `BusinessServiceDefinitions/Deployment.json`; the
resolver is `org.btsn.invocation.BusinessCapabilityResolver`. The orchestration
agent and every `ServiceThread.java` remain unchanged from the recovery baseline.
See `InvocationBoundaryResolver.md` for validation and deployment details.
The formerly inherited stochastic implementation is preserved independently as
`org.btsn.services.StochasticPlaceService`, with its runtime identity supplied by
the caller. It can be selected through the same metadata boundary on any host.

## Acceptance / regression test

Authoritative regression launcher for this branch:

- `../btsn.financial.ProjectLoader/FinancialSystem_Stage5_PriorityPreemption_BuildAndRun.xml`

Expected characteristics:

- v002 starts first and runs 40 irregular arrivals
- v001 starts 10 seconds later and runs 15 arrivals
- both versions share P1/P2
- v001 uses P1-P5 with fork/join
- both versions complete independently
- queue-level priority behaviour is observed by the existing analyzer
- the Gantt chart presents chronological workflow arrival order

The analyzer's current start-order inversion observations are retained as observations;
no additional queue-boundary instrumentation is part of this stable branch.

## Legacy / test artifacts

The following files remain in the repository for historical, development, or
single-node testing purposes and are not the authoritative application definitions:

- `ProcessDefinitionFolder/FinancialSystem.json`
- `ProcessDefinitionFolder/FinancialSystem_P1_Simple.json`
- `../btsn.financial.ProjectLoader/FinancialSystem_P1_Simple_BuildAndRun.xml`
- `../btsn.financial.ProjectLoader/FinancialSystem_Stage3_Concurrent_BuildAndRun.xml`
- `../btsn.financial.ProjectLoader/FinancialSystem_Stage4_LiveDeployment_BuildAndRun.xml`

They should not be treated as competing production definitions of the Financial
application.

## Deferred work

The following changes are deliberately outside this branch-stabilization step:

- moving implementation sources into separate Eclipse projects

`../btsn.services` now builds one deployment JAR per catalogue implementation,
alongside shared service support and runtime libraries. Its packaging inventory
also includes the independently preserved stochastic implementation. All services
use the same packaging mechanism, independent of application and physical host.
Implementation sources remain in `btsn.common`. All six host builds now import
the same generic Ant build, and the Stage-5 launcher compiles and uses the
independent service JARs and shared infrastructure without `bin` directories on
its classpaths. No handler or service implementation changes are involved.
See `../btsn.services/README.md` for build commands and packaged host checks.
