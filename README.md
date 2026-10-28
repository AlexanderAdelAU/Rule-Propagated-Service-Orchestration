# Rule-Propagated Service Orchestration (RPSO)

**RPSO runs Petri nets as live distributed systems.** Each place is a real
service on a host, each transition is coordination logic installed as local
rules on that host, and tokens are messages that travel between hosts. The model
you draw in the editor is the process that executes, not a simulation of it.

Because the places are generic, the same orchestration runs any process you can
express as places, transitions, forks and joins: a Boolean Petri-net model, a
loan application or an emergency-department workflow. Bind different services to
the places and the process does different work. Change the deployment and the
same process runs across different machines. A service can run inside its host
(a Java class) or anywhere it can be reached over REST, written in any language and
hosted locally or on the internet. No central engine routes the
tokens; each host follows its own installed rules.

![An emergency-department process in the editor: patient arrivals pass Triage, whose gateway sends them to Laboratory, Cardiology and Radiology, which join before Diagnosis, then Treatment; a triage canary and federated radiology requests from another hospital enter beside them. Each place runs on its own host, P1 to P6; dashed lines are network connections.](images/emergency-department-concurrent.png)

*For example, three versions of an emergency-department process running at once on six hosts: the full patient workflow (v003), a triage canary (v002) and federated radiology requests from another hospital (v001), each deployed and measured separately.*

How does a drawing like this become a running system? Each place runs on a host of its own:

![The Petri net you draw is the system that runs: a four-place model on the left, where P1 forks to P2 and P3 and they join before P4, is deployed as local rules to four separate hosts on the right. Each host runs its own T_in, place function and T_out; tokens travel between hosts as messages with no central engine, and Monitor observes every host for complete Petri-net analysis.](images/rpso-overview.svg)

*Draw the net, deploy its rules, run it as separate hosts. A place can be any service: a Boolean function, a credit check or a clinical diagnosis.*

Every run is measured as it happens (queue waits, service times, join waits and
end-to-end workflow time) and can be replayed on the model. These are
measurements, not guarantees of meeting hard real-time deadlines.

## From an idea to a measured run

Let's start with something simple. You build a process graphically in the editor, run it on real hosts and replay the
recorded run on the design. [Tutorial.md](Tutorial.md) walks you through two examples:

1. **A standard service** that returns true or false: one place, a loop and ten tokens.
2. **A claims process** that calls a remote service over REST.

You do not need the services before you start. Name them as you draw the process: the
editor writes starting code so the process runs at once, and you replace it with the real
logic one service at a time.

## What you need

- A JDK, version 15 or later (tested with 21).
- Apache Ant 1.10.2 or later. It comes with Eclipse, and the editor finds it there.
- The repository's projects imported into Eclipse (**File → Import → Existing Projects into
  Workspace**). The runtime libraries are supplied; no Maven or Python is needed.

Then start with [Tutorial.md](Tutorial.md).

### Or try it in your browser

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/AlexanderAdelAU/Rule-Propagated-Service-Orchestration?quickstart=1)

The codespace comes with Java, Ant and a desktop in the browser for the editor. In its terminal:

- Run the Dining Philosophers on one machine: `ant -f btsn.petrinet.ProjectLoader/Dining_Philosophers_BuildAndRun.xml -Dhost.address=127.0.0.1 -Dinfrastructure=SingleHost`
- Analyse the run: `ant -f btsn.petrinet.ProjectLoader/Dining_Philosophers_BuildAndRun.xml analyse`
- To see the editor, open the forwarded port 6080 (**Ports** tab), connect with the password `vscode`, then start it with `ant -f btsn.workflowEditor/build.xml run`

## Learn more

The detail is in [Reference.md](Reference.md).

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
