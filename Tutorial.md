# Tutorial: design, deploy, build and run a process

This tutorial takes one process from an empty canvas to a replayed run, all
from ProcessEditor:

- **Design**: a single place P2 calls `StochasticService.processToken`, which
  returns `true` or `false` at random.
- **Routing**: `true` terminates the workflow and `false` sends the same token
  back to P2.
- **Observation**: Monitor records every step from outside the token path.

![ProcessEditor with the P2 tutorial: EG11 feeds T_in_P2, place P2 runs StochasticService.processToken on node P2, T_out_P2 routes true to Terminate and false back to T_in_P2. The Attributes panel shows the catalogue, service, operation, a description of what the service does, the deployment instance, the node P2 runs on and the contract processToken(token) → token.](images/p2-tutorial-design.png)

*The P2 tutorial process. P2 is selected. The badge on the place and the **Runs
on** box show the node it is deployed to.*

| Step | What you do | Where |
|---|---|---|
| **1. Design the process** | Draw the net, choose the service and operation for P2, set the guards, then save | [Design](#1-design-the-process) |
| **2. Deploy the process** | Choose the node P2 runs on, then save the service deployment | [Deploy](#2-deploy-the-process) |
| **3. Create Build and Run** | Choose the event generators and the number of tokens; the editor writes the Ant launcher | [Build and Run](#3-create-build-and-run) |
| **4. Run it** | Click **Run on hosts**: the editor runs the launcher on real hosts, analyses the run and loads the replay | [Run](#4-run-it) |
| **5. Show the results** | Analyse the run, view the charts and replay the tokens on the design | [Results](#5-show-the-results) |
| **6. Add your own service** | Write a service, describe it in a catalogue, and use it like StochasticService (worked example: an inventory and a supplier) | [Your own service](#6-add-your-own-service) |
| **7. Run on several machines** | Describe the machines, package each host for its machine, start them, then run the launcher | [Several machines](#7-run-on-several-machines) |

Finished copies of every file are included as `P2_Tutorial_*` ([list](#repository-artefacts)):

- To follow along, save your own files as `My_P2_Workflow`. That keeps the
  supplied files for comparison.
- Every path below starts at the repository root.

## Before you start

- **Tools**:
  - **JDK 15 or later** and **Apache Ant 1.10.2 or later**. Run Ant with a JDK so its compiler is available.
  - In Eclipse, import the projects with **File → Import → General → Existing
    Projects into Workspace**, leaving **Copy projects into workspace** unchecked.
  - You need `btsn.common`, `btsn.services`, `btsn.common.eventgenerators`,
    `btsn.common.Monitor`, `btsn.petrinet.ProjectLoader`, `btsn.workflowEditor`
    and `btsn.rpso.places.p1`–`p6`.
- **Start the editor**:
  - Run [`com.editor.ProcessEditor`](btsn.workflowEditor/src/com/editor/ProcessEditor.java)
    as a Java application with **btsn.workflowEditor** as its working directory (Eclipse's default).
  - The Deploy panel finds the shared infrastructure from there.
- **Know what a run resets**: every run reinitialises the P2 and Monitor
  databases. Save any results you want to keep before running again.

## 1. Design the process

Choose **File → New → Process Definition** and set the toolbar's **Type** to
**PetriNet**.

### Place the elements

Select a palette tool, click the canvas, then fill in the **Attributes** panel.
Set **Transition Type** before **Node Type**; **Node Value** follows automatically.

| Palette tool | Label | Attributes |
|---|---|---|
| Event Generator | `EG11` | Rate (ms): `1000`; Version: `v001` |
| Transition | `T_in_P2` | Transition Type: `T_in`; Node Type: `EdgeNode`; Buffer: `10` |
| Place | `P2` | Service: `StochasticService`; Operation: `processToken` (see below) |
| Transition | `T_out_P2` | Transition Type: `T_out`; Node Type: `GatewayNode` |
| Transition | `Terminate` | Transition Type: `Other`; Node Type: `TerminateNode` |

- Labels must be unique.
- Name each place's transitions after that place: either its label or the node it is deployed to.
  - Use the same name on the T_in and the T_out.
  - Here both are `P2`, so `T_in_P2` and `T_out_P2`. If you relabel the place `TRUEORFALSE` and deploy it to P2, then `T_in_P2` or `T_in_TRUEORFALSE` both pass.
  - Business models use the label, for example `T_in_Validation`.
- The generator's **Rate** and **Version** become the defaults in
  [Build and Run](#3-create-build-and-run).
- The supplied `P2_Tutorial_Workflow.json` stores `T_out_P2` as a `DecisionNode`. The runtime accepts it too, and both route on the guards below.

### Choose the service and operation

Select P2. Its Attributes panel works from the **service catalogue**: the list
of services and their operations that the hosts can run.

1. **Catalogue**: the panel names the catalogue in use. If the **Service** list is empty, click **Choose catalogue...** and open
   [`BusinessServiceDefinitions/petrinet/PetriNetModels.json`](btsn.common/BusinessServiceDefinitions/petrinet/PetriNetModels.json).
   Once the process is saved under `ProcessDefinitionFolder/petrinet`, the editor selects this catalogue itself.
2. **Service**: hover over each entry in the list to see what it does. Choose `StochasticService`. Its only operation, `processToken`, is
   selected for you.
3. **What it does**: the box under the Operation says what the service does and what it returns, so you know how to route its result:

   > Decides at random: returns true or false with equal chance. Use it to model a step that sometimes has to be repeated, such as a check that can fail.<br>
   > Returns: true or false. Route on it from the T_out with guards true and false.

4. **Contract**: the box shows `processToken(token) → token`. The catalogue
   supplies the arguments; you do not type them.
5. **Runs on**: the box reads **Not deployed yet**. Where P2 runs is decided in [step 2](#2-deploy-the-process).

The Petri-net catalogue also offers `AlwaysTrueService`, which always returns
`true`. Use it in place of `StochasticService` when every run must take the same
route, for example to compare timings on different machines.

Choosing a service does not create one. `StochasticService` is already packaged
([source](btsn.common/src/org/btsn/services/StochasticService.java)) and runs
inside the generic host on whichever node you deploy P2 to.

The descriptions come from the catalogue, where each service has a
`description` and a `returns` entry. The Deploy panel shows the same text when you hover over a Service or Operation cell. When you add a service to a catalogue, describe it there as well; the editor checks require both entries.

### Connect the elements and set the guards

Use **Arrow (drag)**, or **Arrow (click waypoints)** to bend the return route.

| Source | Target | Arrow label | Guard Condition | Decision Value |
|---|---|---|---|---|
| `EG11` | `T_in_P2` | | | |
| `T_in_P2` | `P2` | | | |
| `P2` | `T_out_P2` | | | |
| `T_out_P2` | `Terminate` | `true` | `DECISION_EQUAL_TO` | `true` |
| `T_out_P2` | `T_in_P2` | `false` | `DECISION_EQUAL_TO` | `false` |

- **The label is only a caption.** Guard Condition and Decision Value define the route.
- Leave **Endpoint** blank.
- How a token moves:
  - T_in receives and buffers the token.
  - P2 invokes `processToken`.
  - T_out routes on the result: `false` repeats the visit with the same token, and `true` completes the workflow.
- `StochasticService` returns `true` with probability 0.5, so on average each workflow visits P2 twice.

### Validate and save

1. Click **Validate**. Fix any missing labels, incomplete operations or invalid connections.
2. Choose **File → Save Process Definition As...** and save to
   `btsn.common/ProcessDefinitionFolder/petrinet/Workflow/My_P2_Workflow.json`.

The process must be saved under `ProcessDefinitionFolder/<domain>/`:

- The domain folder (`petrinet`) selects the catalogue, the deployment folder
  and the launcher folder used in the next steps.
- Until it is deployed, the saved file records only its catalogue (`"catalog"`).

## 2. Deploy the process

Deploying decides which **node** (physical host) each place runs on. Each node is one numbered generic host, P1 to P6.

### Choose the node

Use either route:

- **Right-click P2 → Deploy to → P2**: the quick route for a single place.
  - The place shows a dashed **P2** badge, and **Runs on** says *(not saved; use Deploy)*.
  - Use **Deploy panel...** on the same menu to open the panel described next.
- **Click Deploy** in the toolbar: opens the Deploy panel beside the editor, already filled from the process.

![The Deploy panel for the P2 tutorial: the read-only physical node network P1–P6 from SingleHost.json, and one node capability row with Place P2, Node P2, Service StochasticService, Operation processToken, Return Attribute token, Port Slot 0, Instance StochasticInstance1 and Invocation Adapter boolean-token.](images/p2-tutorial-deploy.png)

*The Deploy panel. There is one row per place, and you choose the Node. The other columns are filled for you.*

| Column | Filled with | You change it when |
|---|---|---|
| **Place** | The place label on the canvas | Never; it ties the row to the canvas |
| **Node** | Your right-click choice, or the first free node | You want the place on another host. Edit the cell; the canvas badge follows. |
| **Service / Operation / Return Attribute** | The place's contract from the catalogue | Never; change the design instead |
| **Port Slot** | `0`, the node's first fixed port | A node has a second port (P4, P6) and you need it |
| **Instance** | The place label (`P2`) | You want another name. The supplied file uses `StochasticInstance1`. |
| **Invocation Adapter** | `boolean-token` for a Boolean service | Never for this tutorial |

The **Physical node network** table at the top is read-only. It comes from
[`SingleHost.json`](btsn.common/InfrastructureDefinitionFolder/SingleHost.json):

| Node | Channel | Address | Fixed Base Ports |
|---|---|---|---|
| `P1` | `ip0` | `192.168.1.82` | `4001` |
| `P2` | `ip0` | `192.168.1.82` | `4002` |
| `P3` | `ip0` | `192.168.1.82` | `4003` |
| `P4` | `ip0` | `192.168.1.82` | `4004, 4007` |
| `P5` | `ip0` | `192.168.1.82` | `4005` |
| `P6` | `ip0` | `192.168.1.82` | `4006, 4008` |

- To change the nodes themselves, choose **File → Open → Infrastructure Definition...**.
- For a run on one computer you do not need to edit the address; [step 4](#4-run-it) overrides it.

### Save the deployment, then the process

1. In the panel, click **Save deployment...**. It suggests
   `btsn.common/ServiceDeploymentFolder/petrinet/My_P2_Workflow.json`; accept it.
2. The process now links to that deployment (`"serviceDeployment"`) and P2 takes its instance.
   - The badge turns solid.
   - **Runs on** reads `P2 — 192.168.1.82:4002`.
3. Save the process again with **File → Save Process Definition**.

The saved files link to one another:

- process → service deployment (`serviceDeployment`)
- service deployment → catalogue (`catalog`)

To see where any place runs, open the process and select the place. Bindings and
network rules are generated by the launcher when it runs; there is nothing else
to generate here.

## 3. Create Build and Run

Click **Build and Run** in the toolbar. The editor first checks that:

- the process and its deployment are both saved;
- every place is deployed on one of P1–P6;
- an event generator feeds a deployed place through a `T_in` transition.

It lists anything missing. Healthcare processes use their own token generator
and are not supported here yet.

![The Create Build and Run dialog for the P2 tutorial with a second event generator: 10 tokens per generator, 10 s wait, launcher name P2_Tutorial_Workflow_BuildAndRun.xml in btsn.petrinet.ProjectLoader; an Event generators table with EG11 at v001 and EG6 at v002, both feeding P2_Place.processToken every 1000 ms; and a summary of the files it will write or reuse.](images/p2-tutorial-build-and-run.png)

*Create Build and Run, shown for the P2 tutorial with a second generator, EG6, at v002. The summary marks each file as Write, Reuse or Replace before anything is written.*

| Choice | Default | Meaning |
|---|---|---|
| **Tokens per generator** | `10` | Root workflows each generator starts |
| **Wait after last token (s)** | `10` | Time for the last workflows to finish before collection |
| **Launcher name** | `<process>_BuildAndRun.xml` | File name of the launcher |
| **Folder** | `btsn.<domain>.ProjectLoader` | Where the launcher is written |

The **Event generators** table has one row per generator in the process:

| Column | Default | Meaning |
|---|---|---|
| **Run** | Ticked | Untick a generator to leave it out of this launcher |
| **Feeds** | From the design | The runtime place and operation the generator's tokens go to |
| **Version** | The generator's Version | Rule version for its tokens (`v001`–`v003`) |
| **Interval (ms)** | The generator's Rate | Spacing of its token schedule |

With more than one generator:

- **They all fire together.** Their workflows share the places and compete for them.
- **Each needs its own version.** The version keeps their tokens apart, so Create stays disabled until no two ticked generators share one.
- **The analysis separates them.** It reports each version on its own, and the replay shows all of them.

Click **Create**. For `My_P2_Workflow` the editor writes:

| File | Role |
|---|---|
| `btsn.petrinet.ProjectLoader/My_P2_Workflow_BuildAndRun.xml` | The launcher: your choices as properties, plus an import of the shared run phases |
| `btsn.services/deployments/models/My_P2_WorkflowDeployment.json` | Deployment profile: catalogue, infrastructure and your service deployment. An existing profile for the same deployment is reused. |
| `btsn.common.eventgenerators/EventTriggeringFile/My_P2_Workflow_BuildAndRun.csv` | Token schedule, one `time_ms,0,1` row per token. With several generators, each gets its own file, named after it (for example `..._BuildAndRun_EG6.csv`). |
| `ProcessDefinitionFolder/common/Initializers/P2_Initialization.json` | Resets the hosts and Monitor. Reused when it exists; written for a new set of nodes (for example `P3_P5_Initialization`). |
| `ProcessDefinitionFolder/common/Collectors/P2_Collector.json` | Collects the observations into Monitor. Reused or written the same way. |

- The launcher is about 25 lines; the supplied [`P2_Tutorial_Workflow_BuildAndRun.xml`](btsn.petrinet.ProjectLoader/P2_Tutorial_Workflow_BuildAndRun.xml) is an example.
- The run phases live in
  [`btsn.services/process-runtime.xml`](btsn.services/process-runtime.xml).
- To change a choice, run **Build and Run** again rather than editing the
  launcher. A command-line override also works for a single run ([step 4](#4-run-it)).

## 4. Run it

### Run from the editor

Click **Run on hosts** in the toolbar. The Run window finds the process's launcher and does the whole run:

1. **Builds and starts** the hosts, then follows the launcher step by step:

   **Initialise → Deploy rules → Commitments received → Fire tokens → Collect**

   - **Rules before tokens.** No token is fired until every host has committed to its rules. Each host confirms each version of the rules it is sent, and the launcher waits for those confirmations.
   - **Hosts table.** It shows each host process as it runs: its endpoint, its process number and machine, its state, the rule versions it has committed (for example `v999, v001, v002`) and how many service calls it has completed.
2. **Settles and stops.** When collection is done, it waits for Monitor to finish writing, then stops the launcher and every host it started.
3. **Analyses.** It runs `analyse` and saves the report to `btsn.common/AnalysisFolder/PetriNet/Analysis_My_P2_Workflow.txt`, replacing any earlier analysis of the process.
4. **Loads the replay.** It loads the analysis into the editor and highlights **Play** ([step 5](#replay-the-tokens-on-the-design)).

The output ends with a summary of the run:

- **Per version**, measured: for example `v001: 10 generated, 10 completed, structure OK, latency 275 to 1220 ms (median 663 ms)`. It flags any version whose workflows did not all complete.
- **Where and when it ran**: for example `Ran 17:51:00 to 17:52:15 (75 s) on P2_Place at 127.0.0.1:4002 (process 1084 on my-pc, 41 service calls); Monitor (...)`.

Below it, **Next steps** offers:

| Button | Opens |
|---|---|
| **Replay the recorded run** | The editor, replaying the recorded tokens on the design |
| **Elapsed time and queue wait chart** | Monitor's per-workflow timing chart, for each version |
| **Token spatial view** | Monitor's view of activity at each place over time |
| **Open analysis report** | The full analyzer report |

> **This is a real run, not a simulation.**
>
> - **Real hosts.** Each place runs as its own host process, a generic RPSO host carrying that place's rules. Tokens travel between hosts as network messages on the endpoints shown in the Hosts table.
> - **Real records.** Every host records what it did, with its own timestamps. After the run, Monitor collects those records from the hosts.
> - **A replay, not a model.** The analysis, the charts and the replay are all built from those collected records: the replay shows what happened, in the order and at the times the hosts recorded it.
> - **Across machines.** Untick **Run every host on this computer**, and the same launcher drives hosts on the machines named in `SingleHost.json`.

![The Run window after a completed run of the P2 tutorial: the launcher, the option to run every host on this computer, the Ant location, the analysis file, the seven steps from Build and start to Replay all completed, and the analyzer report in the output.](images/p2-tutorial-run.png)

*A finished run of the P2 tutorial (here with its two generators). Each step turns green as the run reaches it.*

- **Hosts**: **Run every host on this computer** (ticked) adds `-Dhost.address=127.0.0.1`.
- **Ant folder**: leave it empty. The editor finds Apache Ant by itself, trying in order:
  - `ANT_HOME`;
  - `ant` on the PATH;
  - the Ant inside the Eclipse that started the editor (`plugins/org.apache.ant_*`).

  If none is found, choose Ant's folder (the one holding `bin` and `lib`), not a launcher. Ant runs on the editor's own Java, so the editor must run on a JDK (Eclipse's default).
- **Stop**: stops the run, and its hosts, at any point.
- **Close**: closing the window during a run stops it too.
- **Save first**: Run uses the saved process and the launcher from [step 3](#3-create-build-and-run).

### Or run the launcher yourself

- **In Eclipse**: refresh `btsn.petrinet.ProjectLoader`, right-click
  **My_P2_Workflow_BuildAndRun.xml**, then choose **Run As → Ant Build**.
- **From a terminal**:

  ```sh
  ant -f btsn.petrinet.ProjectLoader/My_P2_Workflow_BuildAndRun.xml -Dhost.address=127.0.0.1
  ```

- **On one computer**: `-Dhost.address=127.0.0.1` runs every host on this machine. In Eclipse, put it under
  **Run As → Ant Build... → Main → Arguments**. Without it, a host starts locally only when its `SingleHost.json` address belongs to this computer.

The default target `run-complete-workflow` does everything:

- builds the JARs;
- prepares an isolated runtime under `btsn.services/target/launchers/My_P2_Workflow_BuildAndRun`;
- starts P2 and Monitor;
- runs the three phases.

| Phase | What happens | Definition used |
|---|---|---|
| 1 — Database initialization | Reset P2 and Monitor (administrative version `v999`) | `common/Initializers/P2_Initialization` |
| 2 — Workflow execution | Generate the rules for P2 and send them as `v001`. P2 commits to them (a confirmation per version), and only then are the tokens fired from EG11 | `petrinet/Workflow/My_P2_Workflow` |
| 3 — Data collection | Request P2's observations and write them to Monitor | `common/Collectors/P2_Collector` |

### Stop it when collection finishes

The console ends with `... COMPLETED SUCCESSFULLY` and
`Services are still running. Press Ctrl+C to stop.`

1. Allow Monitor a few seconds to finish writing.
2. Stop the run with Eclipse's red **Terminate** button, or **Ctrl+C** in the terminal. This releases the Monitor database for the analyser and viewers.

Host logs go to `P2_Place.out.txt` and `MonitorService.out.txt` beside the
launcher. The collected data is in `btsn.common.Monitor/ServiceAnalysisDataBase`.

### Change a run without regenerating

| Override | Effect |
|---|---|
| `-Dtoken.count=5` | Fire only the first 5 tokens of the schedule. To fire more than it holds, recreate the launcher with a larger number. |
| `-Dworkflow.completion.seconds=30` | Wait longer after the last token before collecting |
| `-Dhost.address=127.0.0.1` | Run every host on this machine |
| `-Dp2.mode=local` or `remote` | Force a host to start here, or expect it elsewhere |

`ant -f <launcher> help` lists these.

## 5. Show the results

### Analyse the run

After stopping the run:

```sh
ant -emacs -f btsn.petrinet.ProjectLoader/My_P2_Workflow_BuildAndRun.xml analyse > btsn.common/AnalysisFolder/PetriNet/Analysis_My_P2_Workflow.txt
```

- **In Eclipse**: run the launcher's **analyse** target and copy the console
  into that file.
- **Ant's `[java]` prefixes**: they are fine. The editor's replay accepts them.
- **Several analyses in one file**: if you append analyses to the same file, replay uses the last one.

Check the report's first section, **CANONICAL WORKFLOW RECONSTRUCTION**:

- `Generated workflows` and `Completed workflows` should both equal the number
  of tokens.
- `Structural result: [OK]`.
- There should be no forks or joins.

Count workflows separately from place visits:

- Each `false` result makes the same workflow visit P2 again.
- So there are usually more visits than workflows.
- Administrative `v999` activity is reported separately.

The supplied run ([analysis](btsn.common/AnalysisFolder/PetriNet/Analysis_P2_Tutorial_Workflow.txt)) gives:

| Measure | Value |
|---|---|
| Generated / completed workflows | 10 / 10 |
| P2 visits (5 repeats after `false`) | 15 |
| End-to-end latency | 152 to 885 ms, median 260 ms |
| Token inter-arrival | 1,000 ms (from the schedule) |

Boolean outcomes, repeat counts and timings vary from run to run.

### Replay the tokens on the design

1. Open `My_P2_Workflow.json` in ProcessEditor.
2. Click **Load Analysis...** and choose `Analysis_My_P2_Workflow.txt`.
3. Use **Play**, the step buttons and **Speed**.
   - Each token enters at EG11 and is held at P2 while the service runs.
   - It returns along `false`, or leaves along `true` to **Terminate**.

Use the process that produced the run: the replay matches log entries to the places on the canvas. Replaying does not deploy or rerun anything.

### Timing chart and spatial view

After a run from the editor, open these from **Next steps**. Otherwise, run them from the launcher once its hosts have stopped:

```sh
ant -f btsn.petrinet.ProjectLoader/My_P2_Workflow_BuildAndRun.xml chart-timing
ant -f btsn.petrinet.ProjectLoader/My_P2_Workflow_BuildAndRun.xml chart-spatial
```

You can also run them as Java applications from `btsn.common.Monitor`, with that project as the working directory:

| Viewer | What it shows |
|---|---|
| `org.btsn.derby.Analysis.SwingGanttChart_WithLatency` | Elapsed time per workflow, with the longest queue wait observed on a service visit |
| `org.btsn.derby.Analysis.WorkflowSpatialView` | Activity at each place over time, including repeat visits |

![Workflow elapsed-time chart for the captured ten-token P1 reference run.](docs/tutorials/p1/results/workflow-timing.png)

*Elapsed time per workflow from the [captured P1 reference run](docs/tutorials/p1/results/README.md), the same loop on node P1:*

- *Bars run from generation to completion.*
- *Black diamonds mark the longest queue wait on a single service visit, not the total across the loop.*

That reference run has its own launcher and export target. It writes the
analysis, per-workflow CSV and charts to `btsn.services/target/tutorial-results/P1`:

```sh
ant -f btsn.petrinet.ProjectLoader/P1_Tutorial_Local_BuildAndRun.xml
ant -f btsn.petrinet.ProjectLoader/P1_Tutorial_Local_BuildAndRun.xml export-tutorial-results
```

### If your results differ

| Observation | Check |
|---|---|
| Build and Run lists problems | Save the process and the deployment; deploy every place on P1–P6, each on its own node; connect the generator to a `T_in` |
| The Deploy panel's node table is empty | Start ProcessEditor with `btsn.workflowEditor` as its working directory |
| A host or Monitor was skipped at startup | Add `-Dhost.address=127.0.0.1`, or start that host on its configured machine |
| Derby reports another active instance | Stop the previous run, and any viewer holding the database, before analysing |
| Fewer completed workflows than generated | Wait longer before collecting: `-Dworkflow.completion.seconds=30`, or raise **Wait after last token** and recreate the launcher |
| Replay shows no tokens, or tokens skip a place | Load the analysis with the process that produced it, and check it is that run's file |
| A design change did not run | Save the process. If you changed places, redeploy and save the deployment first. |

## 6. Add your own service

StochasticService only tosses a coin. Any Java class can be a place's service, and the rest of the flow (design, Deploy, Build and Run, Run on hosts, replay) stays the same. The supplied **inventory example** shows how, in a deliberately small process: it is an example of extending RPSO, not a full business process.

![The inventory example in the editor: ORDERS feeds the Inventory place on P1; T_out_Inventory routes reserved to Shipped and out_of_stock to the Supplier place on P2, whose T_out routes restocked back to Inventory and unavailable to Cancelled. The Attributes panel shows InventoryService.reserve and what it does.](images/inventory-design.png)

*The inventory example. Inventory runs on P1 and Supplier on P2. The **What it does** box comes from the new catalogue.*

### What it shows

| | Inventory (P1) `InventoryService.reserve` | Supplier (P2) `SupplierService.restock` |
|---|---|---|
| **State** | Stock count, 5 at start | Deliveries left, 1 at start (3 units each) |
| **Routes** | `reserved` to **Shipped**; `out_of_stock` to Supplier | `restocked` back to Inventory, carrying the units; `unavailable` to **Cancelled** |

- **State in a service.** The host keeps the stock between orders, so early orders behave differently from later ones.
- **Routing on any value.** The T_out guards test string results (`reserved`, `out_of_stock`, ...), not just `true` and `false`.
- **Hosts working together.** An order can go from P1 to P2 and back. The hosts share no memory, so the supplier's delivery travels back to the inventory inside the order itself.
- **Real concurrency.** Ten orders always give 8 shipped and 2 cancelled, but which orders are cancelled depends on timing between the hosts. In our runs, order 7 reached the inventory while order 6 was still returning from the supplier with the only delivery; it found no stock, and was cancelled. On a faster machine it could be orders 9 and 10. The replay shows it happening.

### Run it

1. Open [`inventory/Workflow/Inventory_Workflow.json`](btsn.common/ProcessDefinitionFolder/inventory/Workflow/Inventory_Workflow.json).
2. Click **Run on hosts**. The supplied launcher runs P1, P2 and Monitor.

![The Run window after the inventory example: all steps completed; P1_Place and P2_Place each committed rules v999 and v001, with 11 and 3 service calls; and the summary.](images/inventory-run.png)

*P1 made 11 service calls: 10 orders, plus order 6 back from the supplier. P2 made 3: order 6 restocked, and two orders it could not restock.*

### The files for a new service

| File | What to put in it |
|---|---|
| [`InventoryService.java`](btsn.common/src/org/btsn/business/inventory/InventoryService.java), [`SupplierService.java`](btsn.common/src/org/btsn/business/inventory/SupplierService.java) | The services, in a package under `org.btsn.business` |
| [`BusinessServiceDefinitions/inventory/Inventory.json`](btsn.common/BusinessServiceDefinitions/inventory/Inventory.json) | The catalogue: class, operation, inputs, result, `description` and `returns` |
| [`btsn.services/packaging.json`](btsn.services/packaging.json) | Add the catalogue to `catalogues`, and its domain to `catalogueDomains` |
| [`ServiceDeploymentFolder/inventory/Inventory.json`](btsn.common/ServiceDeploymentFolder/inventory/Inventory.json) | Written by **Deploy**: the node each service runs on |
| [`ProcessDefinitionFolder/inventory/Workflow/Inventory_Workflow.json`](btsn.common/ProcessDefinitionFolder/inventory/Workflow/Inventory_Workflow.json) | The process, drawn in the editor |
| [`Inventory_Workflow_BuildAndRun.xml`](btsn.petrinet.ProjectLoader/Inventory_Workflow_BuildAndRun.xml) | Written by **Build and Run** |

### Writing a service

```java
public class InventoryService extends BaseBusinessService {
    public InventoryService(String sequenceId) { ... }          // the host passes the order's token id
    public String reserve(String token) {                        // one String (JSON) per catalogue input
        int delivered = (int)numberValue(token, "delivered_units", 0);
        ...
        result.put("routing_decision", routingDecision(reserved ? "reserved" : "out_of_stock"));
        return businessResult("inventoryResults", result);      // the catalogue's returnAttribute
    }
}
```

- **Its own catalogue and domain.** Use a new folder under `BusinessServiceDefinitions` (here `inventory`). Save the process under a matching `ProcessDefinitionFolder/inventory`, so the editor selects the catalogue, and **Deploy** saves to `ServiceDeploymentFolder/inventory`.
- **Inputs and results.** Each catalogue input arrives as a JSON string. The method's result is `{returnAttribute: {...}}`, and its `routing_decision.routing_path` is the value the guards test.
- **GatewayNode on the T_out.** It routes on the value and passes the whole result on. (A DecisionNode passes on only the route.)
- **State lives in one host.** Use `static` fields, made thread-safe, as the examples do; they start again whenever the host starts. Hosts never share memory: send anything another host needs inside the result.
- **Going back to an earlier place.** Return your result under the name that place takes as input. The supplier returns `token`, which is the inventory's input.
- **Describe it.** The editor checks require `description` and `returns` for every catalogue entry.

## 7. Run on several machines

Hosts talk to each other only by UDP messages, so the same process runs unchanged when its hosts are on different machines. The machine you run the launcher on also runs Monitor and the event generators. Each other machine runs a **host package**: a folder holding everything its host needs.

The worked example spreads the four-place fork/join over three machines. P1 forks to P2 and P3, which run on two other machines, and their results join at P4, back on the first machine. Each branch reaches the join over its own network path.

### Choose the machines

An infrastructure definition gives each node its machine. Three are supplied:

| Definition | Machines |
|---|---|
| [`TwoHost.json`](btsn.common/InfrastructureDefinitionFolder/TwoHost.json) | P1, P3, P5 on 192.168.1.82; P2, P4, P6 on 192.168.1.152. Use it for the inventory example (Supplier on the second machine). |
| [`ThreeHost.json`](btsn.common/InfrastructureDefinitionFolder/ThreeHost.json) | P1, P4 on 192.168.1.82; P2, P5 on 192.168.1.152; P3, P6 on 192.168.1.90. Use it for the fork/join. |
| [`MultiHost.json`](btsn.common/InfrastructureDefinitionFolder/MultiHost.json) | A template for three machines, two nodes on each. |

1. Choose **File → Open → Infrastructure Definition** and open `ThreeHost.json`. Opening it makes it the editor's **Machines** (shown on the toolbar).
2. Change the addresses to your machines and save. The rules:
   - **One channel per machine**, each with that machine's address. Nodes on the same machine share its channel.
   - **`ip0` is the machine you run the launcher on.** Monitor runs there.
   - **Use `ip0` to `ip4`.** The channel number also shifts the ports (`ip1` adds 1000).
3. Open [`P1_P2_P3_P4_Fork_Join_Workflow.json`](btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_P2_P3_P4_Fork_Join_Workflow.json) and select P2. **Runs on** shows `P2 — 192.168.1.152:4002 (ThreeHost)`.

Keep [`SingleHost.json`](btsn.common/InfrastructureDefinitionFolder/SingleHost.json) for runs on one machine: choose it under **Machines** to go back.

### Build the host packages

Click **Build**. The dialog shows the machines, and its summary lists one package for each other machine, named after the hosts it starts:

```
Package  P2 for 192.168.1.152 (Build host packages; start it on its machine)
Package  P3 for 192.168.1.90 (Build host packages; start it on its machine)
```

Click **Build host packages**. It writes the launcher, builds one ZIP per listed machine in `btsn.services/target/host-packages/P1_P2_P3_P4_Fork_Join_Workflow_BuildAndRun/`, and opens that folder. Each package holds:

- **Its hosts**: each host's JAR and the shared infrastructure JAR, with no business classes inside.
- **Their services**: only the business service JARs the deployment places on those hosts, kept as separate JARs.
- **The master ruleset**: the rules and bindings the launcher generated, the same files the hosts on the `ip0` machine read. `README.txt` gives its digest.
- **Launch scripts and a README**: the machine's address, its hosts, their ports and how to start them. A machine with two hosts gets one package, for example `…_P3P6.zip`.

Build the packages again whenever the process, its deployment or the machines change.

From the command line, on the `ip0` machine:

```sh
cd btsn.petrinet.ProjectLoader
ant -f P1_P2_P3_P4_Fork_Join_Workflow_BuildAndRun.xml host-packages -Dinfrastructure=ThreeHost
```

### Start the hosts, then run

1. Copy each ZIP to its machine and extract it. Those machines need Java 15 or later; they do not need Ant or this repository.
2. Start the package's hosts in one of two ways. When Windows asks, let Java use private networks. Wait for `Service Handler is listening` from each host.
   - `launch.bat` (or `sh launch.sh`) starts every host in the package, each in its own window.
   - `launch-to-file.bat` (or `sh launch-to-file.sh`) starts them too, but each writes its output to `Pn_Place.out.txt` in the package folder, the same file a local host writes. Open it in Notepad to follow the run.
   - `launch-Pn.bat` and `launch-to-file-Pn.bat` start a single host.

   Nothing stops a remote host on a timer: it runs until you press Ctrl+C. Keep the machine from sleeping.
3. On the `ip0` machine, click **Run on hosts**, then **Run**. From the command line:

   ```sh
   ant -f P1_P2_P3_P4_Fork_Join_Workflow_BuildAndRun.xml -Dinfrastructure=ThreeHost
   ```

   The launcher starts P1, P4 and Monitor, and skips P2 and P3 (`[p2] Skipping - running on remote host`). It prints `Master ruleset …`, which must match each package's README. It then:
   - deploys the rules to every host and waits for each one to commit;
   - fires the tokens;
   - collects every host's records into Monitor.
4. The editor analyses the run and loads the replay as usual.

**What to look for in the analysis.** The structural results are the same as on one machine:
- **Forks and joins:** `Fork families` equals `Observed join sites`, and `Orphan fork children` is 0.
- **Branch executions:** P2 and P3 each execute once per fork.

The join measures (time from first arrival to ENTER, and the skew between branch arrivals) now include each branch's network path.

### Timing across machines

- **Per-place times are on one clock.** Queue, service and residence times, and the join measures, are differences between timestamps taken by one host, so the clocks of different machines don't affect them.
- **Measures that cross machines also carry the clocks' offset.** The analysis bounds that offset under **Temporal data quality**, for example `clock(P2_Place) - clock(P1_Place) between -291 and -12 ms`, using the rule that a token cannot arrive before it was sent. No setup is needed beyond the operating system's time service.
- **The replay and spatial view use the `ip0` clock.** Places on one channel share a machine's clock. When tokens go to another machine and come back (P1 → P2 → P4 above), its offset is bounded from both sides. The analysis then reports an estimate such as `ip1 [P2_Place]: -22487 ms (bounds -22680..-22294 ms)`, and shifts that machine's times by it for the animation and the spatial view. The database keeps each machine's own timestamps.
- **A machine whose clock is more than 2 minutes ahead of `ip0` drops every token.** Tokens expire 120 s after the event generator creates them, by each receiving machine's own clock. The host's output shows `EVENT REJECTED BY SCHEDULER`. Check the date as well as the time.
- **Coarse clocks on some Windows machines.** On some Windows machines (Windows 7, for example) the clock advances in 15.6 ms steps, so times measured there are resolved to about ±16 ms.

### Network and firewalls

- **Network.** Use a local network or a VPN. Tokens and rules travel as plain UDP, without authentication or encryption.
- **Firewalls.** Each package's README lists the ports its host listens on. The `ip0` machine must accept UDP from the others for Monitor and for rule commitments (ports 35000 and up). If ping between the machines fails, allow ICMP echo in the firewall to check the path.

## 8. Change a running process

A running process changes when a new rule version is published to its hosts. No host is restarted and no code changes. Tokens already in flight finish under the version they started with.

The example is in [`ProcessDefinitionFolder/petrinet/LiveChange`](btsn.common/ProcessDefinitionFolder/petrinet/LiveChange). Every place runs `AlwaysTrueService`, so only the rules decide the route:

| File | What it is |
|---|---|
| `P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange.json` | The combined process, named after its launcher: an amalgam of the two processes below, with EG1, EG2 and P6 drawn as a standby service. Open it in the editor. **Build** says it is a combined process, shows what its launcher runs, and builds the host packages; you copy them to their machines and start them; then **Run on hosts** runs the launcher of the same name. |
| `P1_P2_P3_P4_ForkRebalance_Workflow.json` | `v001`: P1 forks to P2 and P3, which join at P4 |
| `P1_P3_P4_P6_ForkRebalance_Workflow.json` | `v002`: the same, but the second branch runs on P6 |

One launch runs both, [`P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange.xml`](btsn.petrinet.ProjectLoader/P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange.xml), like the healthcare launchers that run several workflows together:
1. Every host is started and initialised, P6 included. P6 gets no `v001` rules, so it does nothing.
2. EG1 fires 90 `v001` tokens, one a second.
3. Halfway through, at 45 s, the need for P6 is assumed. `v002` is deployed to the running hosts. The `[LIVE]` lines give the time its publish started and the time every host had committed it.
4. EG2 fires 30 `v002` tokens while `v001` is still firing.

On `ThreeHost`:
- **192.168.1.152**: unzip `…_LiveChange_P2.zip` and run `launch-to-file.bat` (P2).
- **192.168.1.90**: unzip `…_LiveChange_P3P6.zip` and run `launch-to-file.bat` (P3 and P6).
- **192.168.1.82**: run the launcher, and nothing else on this machine at the same time; then run its `analyse` target.

```sh
cd btsn.petrinet.ProjectLoader
ant -f P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange.xml host-packages     # the packages for .152 and .90
ant -f P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange.xml                   # the run, on 192.168.1.82
ant -f P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange.xml analyse
```

To run everything on one machine, add `-Dinfrastructure=SingleHost -Dhost.address=127.0.0.1` to the run.

**What to look for:**
- **Both versions:** the analysis has a section for `v001` (workflow base 1000000) and one for `v002` (2000000).
- **Routes:** under `LOGICAL PLACE EXECUTIONS`, there is no P6 in `v001` and no P2 in `v002`.
- **Nothing lost:** generated and completed workflows are equal for each version.
- **Forks and joins:** each version has as many forks and joins as workflows, and `Join-family violations: 0`.
- **Overlap:** both versions run on P1, P3 and P4 at the same time (`CROSS-VERSION PRIORITY ANALYSIS` lists them as shared services).

**What travels by message.** A version's routing rules reach the hosts as rule messages. Its service contracts are read from each host's `btsn.common`. The two versions share their contracts (P6 has P2's), so `v002` needs nothing new on the hosts.

## Repository artefacts

The supplied P2 tutorial, built with exactly these steps:

| Artefact | Role |
|---|---|
| [P2_Tutorial_Workflow.json](btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P2_Tutorial_Workflow.json) | Process design, linked to its deployment |
| [P2_Tutorial.json](btsn.common/ServiceDeploymentFolder/petrinet/P2_Tutorial.json) | Service deployment: P2 on node P2, linked to its catalogue |
| [PetriNetModels.json](btsn.common/BusinessServiceDefinitions/petrinet/PetriNetModels.json) | Petri-net service catalogue (`StochasticService`) |
| [SingleHost.json](btsn.common/InfrastructureDefinitionFolder/SingleHost.json) | Shared nodes P1–P6, addresses and fixed ports |
| [P2_Tutorial_WorkflowDeployment.json](btsn.services/deployments/models/P2_Tutorial_WorkflowDeployment.json) | Deployment profile written by Build and Run |
| [P2_Tutorial_Workflow_BuildAndRun.xml](btsn.petrinet.ProjectLoader/P2_Tutorial_Workflow_BuildAndRun.xml) | Launcher written by Build and Run |
| [`P2_Tutorial_Workflow_BuildAndRun*.csv`](btsn.common.eventgenerators/EventTriggeringFile) | Token schedules, one per event generator |
| [P2_Initialization.json](btsn.common/ProcessDefinitionFolder/common/Initializers/P2_Initialization.json) / [P2_Collector.json](btsn.common/ProcessDefinitionFolder/common/Collectors/P2_Collector.json) | Administrative processes for node P2 |
| [Analysis_P2_Tutorial_Workflow.txt](btsn.common/AnalysisFolder/PetriNet/Analysis_P2_Tutorial_Workflow.txt) | Analysis of a ten-token run, ready to replay |
| [process-runtime.xml](btsn.services/process-runtime.xml) | Shared phases imported by every generated launcher |

The P1 reference run:

| Artefact | Role |
|---|---|
| [P1_Tutorial_Workflow.json](btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_Tutorial_Workflow.json) | The same loop on node P1 |
| [P1_Tutorial_Local_BuildAndRun.xml](btsn.petrinet.ProjectLoader/P1_Tutorial_Local_BuildAndRun.xml) | Local launcher with the `export-tutorial-results` target |
| [P1_Tutorial_Generated_BuildAndRun.xml](btsn.services/tests/launchers/P1_Tutorial_Generated_BuildAndRun.xml) | The same process as Build and Run writes it (used by the launcher checks) |
| [Captured results](docs/tutorials/p1/results/README.md) | Analyzer output, per-workflow CSV, charts and capture details |

## Run an example

Every launcher runs the same way:

- right-click it, then choose **Run As → Ant Build** (default target `run-complete-workflow`);
- add `-Dhost.address=127.0.0.1` to run on one computer;
- afterwards, run its `analyse` target.

| Example | Ant entry point |
|---|---|
| Single place (this tutorial) | [P2_Tutorial_Workflow_BuildAndRun.xml](btsn.petrinet.ProjectLoader/P2_Tutorial_Workflow_BuildAndRun.xml) |
| Single place, P1 reference | [P1_Tutorial_Local_BuildAndRun.xml](btsn.petrinet.ProjectLoader/P1_Tutorial_Local_BuildAndRun.xml) |
| Your own service: inventory and supplier | [Inventory_Workflow_BuildAndRun.xml](btsn.petrinet.ProjectLoader/Inventory_Workflow_BuildAndRun.xml) |
| P1–P4 fork/join model | [P1_P2_P3_P4_BuildAndRun.xml](btsn.petrinet.ProjectLoader/P1_P2_P3_P4_BuildAndRun.xml) |
| Six-place double-join model | [P1_to_P6_Double_Join_Workflow.xml](btsn.petrinet.ProjectLoader/P1_to_P6_Double_Join_Workflow.xml) |
| Emergency department | [Emergency_Department_BuildAndRun.xml](btsn.healthcare.ProjectLoader/Emergency_Department_BuildAndRun.xml) |
| Full Financial application | [FinancialSystem_P1_P5_BuildAndRun.xml](btsn.financial.ProjectLoader/FinancialSystem_P1_P5_BuildAndRun.xml) |
| Concurrent healthcare versions | [Triple_Workflow_Emergencey_Department_Concurrent.xml](btsn.healthcare.ProjectLoader/Triple_Workflow_Emergencey_Department_Concurrent.xml) |

- **File name**: the concurrent launcher's name contains the typo `Emergencey`; use it as named.
- **More scenarios**: the loader guides list further scenarios:
  [healthcare](btsn.healthcare.ProjectLoader/README.md),
  [Financial](btsn.financial.ProjectLoader/README.md) and
  [Petri-net models](btsn.petrinet.ProjectLoader/README.md).
- **Remote hosts**: start them from host packages built for the same deployment; see
  [Run on several machines](#7-run-on-several-machines).
- **One run at a time**: stop one run before starting another on the same ports.

## Author

Alexander Cameron
