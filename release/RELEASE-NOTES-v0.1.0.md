# RPSO v0.1.0 — Windows Distribution

## Getting started on Windows

1. Download **RPSO-v0.1.0-Windows.zip** from **Assets** below and extract the entire ZIP into a writable folder.
2. Double-click **`START_RPSO.bat`** in the extracted folder. The **RPSO Process Editor** opens.
3. Select **File → Open → Process Definition...**. The file picker starts in the ProcessDefinitionFolder, so simply choose **`petrinet → Workflow → P1_Tutorial_Workflow.json`**.
4. Choose **Run on hosts**, leave **Machines** set to **SingleHost**, and click **Run**. The tutorial runs on this computer (`127.0.0.1`), so you don't need to enter an IP address.
5. When the run finishes, select **Replay the recorded run** to see what happened.

**Requirement:** Windows with a Java Development Kit (JDK 17 or 21 recommended).

If you see an **Ant folder** field in the Run window, leave it blank.

### What's included

RPSO Process Editor, orchestration hosts, Monitor, example workflows and launchers, and the project sources. For a more detailed walkthrough and other examples, see `README-FIRST.md` in the extracted folder.

Advanced multi-host runs require an explicitly selected and configured infrastructure. Business services hosted on Vercel remain remote.

**Please note:** RPSO v0.1.0 is experimental research and design software, not a production workflow engine. Use test services and test data only.
