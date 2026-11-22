# RPSO v0.1.0 — Windows Distribution

## Getting started on Windows

**Simply download the Windows ZIP from Assets, extract the entire ZIP into a writable folder, and double-click `START_RPSO.bat` in the extracted top-level folder.** This launches the RPSO Process Editor.

That's it for installation: **Apache Ant is included**, and you do not need Eclipse or a separate Ant installation. You do need a Windows computer with a Java Development Kit (JDK 17 or 21 recommended).

To try the supplied payment workflow:

1. In the Process Editor, select **File → Open → Process Definition...** and open `btsn.common\ProcessDefinitionFolder\payments\Workflow\Remote_Payment_Test.json`.
2. Leave **Machines** set to **SingleHost** and choose **Run on hosts**. Orchestration hosts use `127.0.0.1` automatically, so there is **no IP address to configure** for a local run.
3. Leave the **Ant folder** empty, and click **Run**. Once complete, select **Replay the recorded run** to inspect the results.

The example payment, credit and claims services hosted on Vercel remain remote, so those examples require internet access. Multi-host orchestration is available by explicitly selecting and configuring a distributed infrastructure.

### Included

Process Editor, RPSO orchestration hosts, Monitor, example projects and workflows, launchers, service libraries, and bundled Apache Ant.

Read `README-FIRST.md` inside the extracted ZIP for the complete first-run instructions and illustrated walkthrough.

**Please note:** RPSO v0.1.0 is experimental research and design software, not a production workflow engine. Use test services and test data only.
