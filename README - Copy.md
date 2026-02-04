# Rule-Propagated-Service-Orchestration

A decentralized workflow orchestration architecture that eliminates central coordination bottlenecks by embedding orchestration logic as executable rules at service boundaries.

## Overview

Rather than services being passive executors controlled by a remote orchestrator, coordination intelligence is embedded in control nodes (T_in and T_out) at service boundaries. Services remain focused on business logic while the control nodes make autonomous routing decisions based on locally-executed rules, maintaining global workflow coherence through token-based state propagation.

![Architecture](images/Architecture.png)

*Dual-layer architecture: Rule distribution (compile-time) and token flow (runtime)*

## Key Features

- **Distributed Orchestration** - No central engine bottleneck or single point of failure
- **Rule-Based Coordination** - Services execute RuleML rules locally via OOjDREW engine
- **Token-Based State** - Workflow state propagates with tokens, eliminating external state stores
- **Bounded Failure Impact** - Node failures affect only dependent workflows (20-33%) vs 100% in centralized systems
- **Concurrent Versioning** - Multiple workflow versions (v001, v002, v003) execute simultaneously
- **Geographic Distribution** - Native support for distributed deployments

## Architecture

The system implements a two-layer architecture:

- **Rule Distribution Layer** (compile-time) - Transforms DOT workflow specifications into service-specific rule fragments
- **Token Flow Layer** (runtime) - Tokens traverse the service network with embedded orchestration at each node

### Core Components

| Component | Description |
|-----------|-------------|
| ServiceThread | Embedded orchestrator coordinating all components |
| EventReactor | UDP-based token reception with buffering |
| Rule Handler | Receives and validates rule fragments |
| OOjDREW Engine | RuleML query processing for routing decisions |
| EventPublisher | Intelligent token routing to downstream services |
| ServiceHelper | Multi-protocol service invocation |

### Coordination Patterns

- **DecisionNode** - Conditional routing based on service results
- **ForkNode** - Parallel service invocation
- **JoinNode** - Correlation-based synchronization
- **MergeNode** - Flexible input handling

## Project Structure

```
├── btsn.common/                    # Shared libraries and rules
│   ├── src/org/btsn/              # Common source code
│   ├── lib/                        # Dependencies
│   ├── RuleBase/                   # Rule definitions
│   ├── ServiceAttributeBindings/   # Service bindings
│   └── serviceLoaderQueries/       # Loader configurations
│
├── btsn.healthcare.places.Triage/      # Triage service
├── btsn.healthcare.places.Cardiology/  # Cardiology service
├── btsn.healthcare.places.Diagnosis/   # Diagnosis service
├── btsn.healthcare.places.Laboratory/  # Laboratory service
├── btsn.healthcare.places.Radiology/   # Radiology service
└── btsn.healthcare.places.Treatment/   # Treatment service
```

## Requirements

- Java 15+
- Apache Ant
- OOjDREW rule engine
- Derby/MySQL (optional)

## Building

Each service can be built independently:

```bash
cd btsn.healthcare.places.Triage
ant clean release
```

This produces a distributable ZIP containing the service JAR, dependencies, and launch scripts.

## Configuration

### Rule Distribution

Rules are distributed via UDP with version-based port calculation:
- Rule listener: port 20000 + (channel × 1000) + basePort
- Commitment acknowledgment: port 30000

### Service Registration

Services register via RuleML atoms:

```xml
<!-- Local deployment (multicast) -->
<Atom><Rel>hasOperation</Rel>
  <Ind>DiagnosisService</Ind>
  <Ind>processClinicalDecision</Ind>
  <Ind>a4</Ind>
  <Ind>1024</Ind>
</Atom>

<!-- Remote deployment -->
<Atom><Rel>activeService</Rel>
  <Ind>DiagnosisService</Ind>
  <Ind>processClinicalDecision</Ind>
  <Ind>ip0</Ind>
  <Ind>1020</Ind>
</Atom>
```

## Validation Scenario

The implementation includes an emergency department workflow with three paths:

1. **Fast Track** - Direct triage to treatment (20% of cases)
2. **Comprehensive** - Triage → {Radiology, Laboratory, Cardiology} → Diagnosis → Treatment
3. **Federated** - External radiology requests from other hospitals

## Tutorial: Running the Traffic Lights Simulation

1. Expand project **btsn.petrinet.ProjectLoader**

2. Run the Ant build file **TrafficLight_BuildAndRun**

3. When complete, open project **btsn.common.Monitor**, then open `org.btsn.derby.Analysis` and run **PetriNetAnalyzer** to confirm the analysis was captured and valid

4. Copy the analysis results to: `btsn.common/AnalysisFolder/PetriNet/Analysis_TrafficLights.txt`

5. To run the animator, open **btsn.ProcessEditor/com/editor/ProcessEditor**

6. Open the local ProcessDefinitionFolder in common and select the process: `PetriNet/TrafficLights.json`

7. Load the analysis file from the Analysis folder where the run was saved

8. Press **Run** to see the simulation results

## License

[Specify license]

## Author

Alexander Cameron
