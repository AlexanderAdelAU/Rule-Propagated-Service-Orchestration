# RPSO v0.1.0 — first run (Windows)

This experimental distribution contains the Process Editor, the shared RPSO
orchestration hosts, Monitor, event generators, service libraries, project
sources, example workflows and their Ant launchers.

RPSO is a **research and design platform**, not a production workflow engine.
Tokens are held in memory and host-to-host UDP messages are not retransmitted.
Use test services and test data, not live financial or clinical workloads.

## Requirements

- Windows with a **JDK 15 or later** installed; JDK 17 or 21 is recommended.
  Check that both `java -version` and `javac -version` work in a command prompt.
- **Apache Ant 1.10.2 or later**, installed with `ant` on PATH, supplied through
  `ANT_HOME`, or selected in the editor's **Run on hosts** window. Eclipse is
  optional; RPSO uses Ant to compile and launch its hosts.
- Network access to a test REST service for the supplied payment example.

## Run the included payment test

1. Unzip the **entire** distribution into a normal, writable folder, for
   example `C:\RPSO`. Keep the `btsn.*` directories together; their relative
   paths are part of the runtime configuration.
2. Double-click **`START_RPSO.bat`** in the extracted top-level folder.
3. In the editor, choose **File → Open → Process Definition...** and open
   `btsn.common\ProcessDefinitionFolder\payments\Workflow\Remote_Payment_Test.json`.
4. Set **Machines** to `SingleHost`, then click **Run on hosts**. Leave
   **Run every host on this computer** selected and click **Run**. If asked
   whether to rebuild, use the supplied launcher (**Run as it was built**).
5. Wait for **Run complete**, then select **Replay the recorded run** and
   inspect the results. The test sends 30 payments to a deliberately imperfect
   *mock* REST service; some should be declined or fail. Internet access is
   required, and results depend on the availability of that test endpoint.

The detailed, illustrated walkthrough is in
[`guides/run-the-payment-test.md`](guides/run-the-payment-test.md), relative to
the extracted folder. For more examples, read `Tutorial.md`.

## Working with your own processes

The editor's **Build** and **Run on hosts** operations use Ant to package
and launch the services and orchestration hosts. Source and project structure
are included because the editor can generate and build new Java services,
new process launchers and configuration files. Your changes remain local.

Use the repository's `README.md`, `Architecture.md` and `Reference.md` for
the system design and advanced multi-host deployment instructions.

## Rebuilding this distribution

From the repository root, with a JDK and Ant installed:

```bat
ant -f release\build.xml dist
```

The versioned ZIP will appear in `release\dist`. The build compiles the
services, host runtimes, Monitor, event generators and editor before archiving.
This ZIP contains a complete runnable **project workspace**, not a native
Windows installer and not a bundled Java runtime.

Copyright (c) Alexander Cameron. Refer to the licensing terms in `README.md`.
