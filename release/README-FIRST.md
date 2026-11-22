# RPSO v0.1.0 — first run (Windows)

This experimental distribution contains the Process Editor, the shared RPSO
orchestration hosts, Monitor, event generators, service libraries, project
sources, example workflows, their launchers and Apache Ant.

RPSO is a **research and design platform**, not a production workflow engine.
Tokens are held in memory and host-to-host UDP messages are not retransmitted.
Use test services and test data, not live financial or clinical workloads.

## Requirements

- Windows with a **JDK 15 or later** installed; JDK 17 or 21 is recommended.
  Check that both `java -version` and `javac -version` work in a command prompt.
- **No Ant or Eclipse installation is needed.** `START_RPSO.bat` uses the
  Apache Ant runtime already provided in `tools\ant`. Leave the **Ant folder**
  box empty in the editor; only select a folder if deliberately using your own Ant.
- Network access to a test REST service for the supplied payment example.

By default, **all RPSO orchestration hosts run on this computer** (`SingleHost`,
`127.0.0.1`), across the example workflows and generated launchers. This does
not relocate the business services they call: the payment, credit and claims
examples still contact their configured remote REST endpoints on Vercel.
To distribute the orchestration hosts themselves, explicitly select a
multi-host infrastructure and configure that infrastructure's addresses.

## Run the included payment test

1. Unzip the **entire** distribution into a normal, writable folder, for
   example `C:\RPSO`. Keep the `btsn.*` directories together; their relative
   paths are part of the runtime configuration.
2. Double-click **`START_RPSO.bat`** in the extracted top-level folder. A newly
   extracted location starts with `SingleHost`, even if another RPSO checkout
   previously selected `MultiHost`. Later launches from this location remember
   your choice.
3. In the editor, choose **File → Open → Process Definition...** and open
   `btsn.common\ProcessDefinitionFolder\payments\Workflow\Remote_Payment_Test.json`.
4. Confirm **Machines** already reads `SingleHost`, then click **Run on hosts**. The bundled
   `SingleHost.json` uses `127.0.0.1` (this computer), so **you do not need to
   find or enter an IP address**. Leave **Run every host on this computer**
   selected and the **Ant folder** blank, then click **Run**. If asked
   whether to rebuild, use the supplied launcher (**Run as it was built**).
5. Wait for **Run complete**, then select **Replay the recorded run** and
   inspect the results. The test sends 30 payments to a deliberately imperfect
   *mock* REST service; some should be declined or fail. Internet access is
   required, and results depend on the availability of that test endpoint.

The detailed, illustrated walkthrough is in
[`guides/run-the-payment-test.md`](guides/run-the-payment-test.md), relative to
the extracted folder. For more examples, read `Tutorial.md`.

## Working with your own processes

The editor's **Build** and **Run on hosts** operations use the bundled Ant to package
and launch the services and orchestration hosts. Source and project structure
are included because the editor can generate and build new Java services,
new process launchers and configuration files. Your changes remain local.

Use the repository's `README.md`, `Architecture.md` and `Reference.md` for
the system design and advanced multi-host deployment instructions.
For **multi-computer deployments**, open the relevant file in
`btsn.common\InfrastructureDefinitionFolder` and replace its example
`192.168.*` addresses with the actual IP address of each host computer.
The `127.0.0.1` default applies only when running locally.

## Rebuilding this distribution

From the **source repository** root, with a JDK and Ant installed on the
build machine (the released ZIP already contains Ant for end users):

```bat
ant -f release\build.xml dist
```

The versioned ZIP will appear in `release\dist`. The build compiles the
services, host runtimes, Monitor, event generators and editor before archiving.
This ZIP contains a complete runnable **project workspace**, not a native
Windows installer and not a bundled Java runtime.

The included Apache Ant JARs are distributed under Apache License 2.0; see
`tools\ant\LICENSE.txt` and `tools\ant\NOTICE.txt` in the extracted package.

Copyright (c) Alexander Cameron. Refer to the licensing terms in `README.md`.
