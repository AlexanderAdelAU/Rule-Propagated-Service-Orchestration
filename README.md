# Rule-Propagated Service Orchestration (RPSO)

**RPSO uses Petri-net theory to transform ordinary callable services into a logically defined, distributed orchestration system.**

A service, whether a Java method or a REST endpoint, is represented as a Place within a logical **Node (T_in → Place → T_out)**. This gives each service an explicit orchestration context: how its inputs are collected, when it can execute, and how its results are propagated to other services.

The Petri-net representation provides a formal foundation for sequencing, conditional routing, concurrency, forks, joins and synchronisation, without requiring these behaviours to be implemented within the business services themselves.

**RPSO turns this logical representation into a live distributed system.** Nodes are deployed to generic [Orchestration Hosts](Architecture.md#terms), where transitions become locally evaluated rules and tokens become messages exchanged directly between hosts. There is no central orchestration engine directing their execution.

The process drawn in the editor is therefore more than a visual model: **it is the definition of the system that executes.** Its behaviour can be observed, measured, analysed and replayed against the original Petri-net representation.

Because the orchestration machinery is generic, it can execute very different
Petri-net processes: from a Boolean test to a loan application or an
emergency-department workflow. Bind different services to the Places to change
the work performed; change the deployment to run the same process across
different machines. A Java service can run in-process inside its orchestration
host, while a REST service can run anywhere its endpoint is reachable, in any
language.

![An emergency-department process in the editor: patient arrivals pass Triage, whose gateway sends them to Laboratory, Cardiology and Radiology, which join before Diagnosis and Treatment. A triage canary and federated radiology requests from another hospital also enter the process. Places are mapped to orchestration hosts P1 to P6; dashed lines show network connections.](images/emergency-department-concurrent.png)

**Reading the diagram**
- **Circles** represent Places: the logical processing steps, each bound to a service (Triage, Laboratory, …).
- **P1–P6 badges** show where each logical Node (T_in → Place → T_out) is deployed. A generic orchestration host executes its behaviour.
- **Bars** on either side of a Place represent its T_in and T_out transition behaviour. T_in (grey) gathers inputs; T_out (yellow) propagates results. The deployed host carries out both using installed rules.
- **Letters** on the bars identify their control roles: **E** edge, **G** gateway (routes by the service's decision), **J** join (waits for every input), **M** merge (takes either input), **T** terminate.
- **EG** is an event generator, which starts the tokens for one process version.
- **Guarded arcs** leave a gateway. A route is taken when the service's decision matches the arc's guard. Laboratory, Cardiology and Radiology share the guard STANDARD_WORKFLOW, so all three receive the work-up request; DIRECT_TO_TREATMENT selects the direct route. Labels v001–v003 show which versions use an arc.
- **Dashed arcs** indicate network connections between hosts; a **2** on an arc marks a Place with two operations.

*This example shows three versions of an emergency-department process running concurrently on six hosts: the full patient workflow (v003), a triage canary (v002) and federated radiology requests from another hospital (v001). Each is deployed and measured separately. [Browse more examples by category](https://alexanderadelau.github.io/Rule-Propagated-Service-Orchestration/docs/replay/examples.html).*

[▶ Watch a recorded run of this process](https://alexanderadelau.github.io/Rule-Propagated-Service-Orchestration/docs/replay/?model=../../btsn.common/ProcessDefinitionFolder/healthcare/Workflow/Emergency_Department_With_Federated_Radiology.json&run=../../btsn.common/AnalysisFolder/Healthcare/Emergency_Department_With_Federated_Radiology.txt)

How does a drawing become a running system? In this four-Node example, each Node is deployed to a separate orchestration host:

![A four-place Petri-net model on the left, where P1 forks to P2 and P3 before joining at P4, is deployed as local rules to four separate hosts on the right. Each host carries out T_in, invokes the bound Place Service and handles T_out. Tokens travel directly between hosts; Monitor collects their records afterward for Petri-net analysis.](images/rpso-overview.svg)

*Draw the net, deploy its rules and run it across hosts. A Place can be bound to anything from a Boolean function to a credit check or a clinical service.*

[▶ Watch a recorded run of this process](https://alexanderadelau.github.io/Rule-Propagated-Service-Orchestration/docs/replay/?model=../../btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_P2_P3_P4_Fork_Join_Workflow.json&run=../../btsn.common/AnalysisFolder/PetriNet/Analysis_P1_P2_P3_P4_Fork_Join_Workflow.txt)

During execution, each host records events that can later be analysed for queue
waits, service times, join waits and end-to-end workflow time. The recorded run
can then be replayed against the model. These are measurements, not guarantees
of meeting hard real-time deadlines.

## From an idea to a measured run

Start by drawing a process in the editor, running it on orchestration hosts and
replaying the recorded execution on the same model. [Tutorial.md](Tutorial.md)
walks through two examples:

1. **A standard service** that returns true or false: one place, a loop and ten tokens.
2. **A claims process** that calls a remote service over REST.

The business services do not have to exist before you start modelling. Name them
as you draw: the editor can generate starting Java code, allowing you to run the
process and replace the starting implementations with real business logic one
service at a time.

## What you need

- A JDK, version 15 or later (tested with 21).
- Apache Ant 1.10.2 or later. It comes with Eclipse, and the editor finds it there.
- The repository's projects imported into Eclipse (**File → Import → Existing Projects into
  Workspace**). The runtime libraries are supplied; no Maven or Python is needed.

Then start with [Tutorial.md](Tutorial.md).

## Learn more

For detailed instructions and technical background, see [Reference.md](Reference.md).

**Using the editor**

- [The editor, step by step](Reference.md#the-editor-step-by-step)
- [Adding services while designing](Reference.md#adding-services-while-designing): catalogues, starting code and what Validate checks
- [Each step in detail](Reference.md#each-step-in-detail): design, deploy, Build and Run, results, several machines, changing a running process
- [Writing a service](ServiceTutorial.md), in Java or behind a REST endpoint in any language

**Running and observing**

- [Quick start: run, observe and replay](Reference.md#quick-start-run-observe-and-replay), from the editor or the command line
- [Run an example](Reference.md#run-an-example): every supplied launcher
- [Observe and interpret a run](Reference.md#observe-and-interpret-a-run)

**How RPSO works**

- [How RPSO is implemented](Architecture.md): terminology, runtime programs, hosts, rule deployment, token execution, run phases and network ports
- [Inside each host](Reference.md#inside-each-host)
- [From the architecture pattern to a running service](Reference.md#from-the-architecture-pattern-to-a-running-service): from one place to six, and the financial and healthcare workflows
- [Architecture: coordination and business meaning](Reference.md#architecture-coordination-and-business-meaning)
- [Rule deployment and token execution](Reference.md#rule-deployment-and-token-execution)
- [Execution examples](Reference.md#execution-examples), including the [concurrent healthcare run](Reference.md#concurrent-healthcare-execution) pictured above

**The repository**

- [Project layout and build support](Reference.md#project-layout-and-build-support)
- [Repository artefacts](Reference.md#repository-artefacts)

## License

The licensing terms for the software and accompanying documentation follow.

**Copyright (c) 2025 Alexander Cameron**

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to use, copy, modify, merge, and distribute the Software for non-commercial purposes, subject to the following conditions: the above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

**Commons Clause Restriction:** The grant of rights under this license does not include the right to Sell the Software. "Sell" means providing the Software, or any derivative work, to third parties for a fee or other consideration, or offering a product or service whose value derives substantially from the Software's functionality. For commercial licensing enquiries, contact [your email].

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED.

## Author

Alexander Cameron
