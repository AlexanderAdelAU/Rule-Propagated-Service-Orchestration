# From an idea to a measured run

We will walk you through three examples. All are built graphically in the editor.

- **Example 1** runs a standard service that returns true or false.
- **Example 2** builds a simple claims process that calls a remote service over REST.
- **Example 3** adds a seventh host, P7, and moves Example 1 onto it.

**Before you start:** you need a JDK (15 or later), Apache Ant (it comes with Eclipse), and
the repository's projects imported into Eclipse.

## Example 1: a standard service

The process has one place, **P1**. Its service returns `true` or `false` at random.

- `true` ends the workflow.
- `false` sends the token back to P1 to try again.

The event generator sends 10 tokens, one a second.

![The finished Example 1 in the editor: P1_EVENTGENERATOR feeds T_in_P1, place P1 runs StochasticService on node P1, and T_out_P1 sends true to Terminate and false back to T_in_P1.](images/ex1-model.png)

*What you will build.*

### Create the process

1. Start the editor: in Eclipse, run `com.editor.ProcessEditor` (project `btsn.workflowEditor`) as a Java application.
2. Choose **File → New → Process Definition**.
3. Choose **File → Save Process Definition As...** and save it as
   `btsn.common/ProcessDefinitionFolder/petrinet/Workflow/My_P1_Workflow.json`.

### Add the elements

For each element: click its tool in the palette (top left), click the canvas, then type
its **Label** in the Attributes panel.

4. **Event Generator**: label `P1_EVENTGENERATOR`.
5. **Transition**: label `T_in_P1`.
6. **Place** (the circle): label `P1`.
7. **Transition**: label `T_out_P1`.
8. **Transition**: label `Terminate`.

![The five elements in a row: P1_EVENTGENERATOR, T_in, P1, T_out and Terminate, not yet connected.](images/ex1-elements.png)

### Connect them

Click **Arrow (drag)**, then drag from one element to the next. Click the tool again for each arrow.

9. `P1_EVENTGENERATOR` → `T_in_P1`
10. `T_in_P1` → `P1`
11. `P1` → `T_out_P1`
12. `T_out_P1` → `Terminate`
13. `T_out_P1` → `T_in_P1`: use **Arrow (click waypoints)** so the arrow goes over P1.
    Click `T_out_P1`, click two points above the place, then click `T_in_P1`.

### Set the details

Select each element or arrow, then change it in the Attributes panel.

14. `P1`: **Service** `StochasticService`. Its operation, `processToken`, is chosen for you.
15. `T_out_P1`: **Node Type** `GatewayNode`.
16. `Terminate`: **Node Type** `TerminateNode`.
17. The arrow `T_out_P1` → `Terminate`: **Guard Condition** `DECISION_EQUAL_TO`,
    **Decision Value** `true`, **Label** `true`.
18. The arrow `T_out_P1` → `T_in_P1`: **Guard Condition** `DECISION_EQUAL_TO`,
    **Decision Value** `false`, **Label** `false`.
19. Click **Validate**. You should see **Validation passed!**
20. Choose **File → Save Process Definition**.

### Deploy it

21. Click **Deploy**. In P1's row, choose **Host node** `P1`.
22. Click **Save deployment...** and accept the name it suggests.
23. Choose **File → Save Process Definition**.

![The Deploy panel: one row for place P1, on host node P1 at 192.168.1.82:4001, running StochasticService.processToken.](images/ex1-deploy.png)

### Run it

24. Click **Build**. The summary lists `Hosts    P1 and Monitor`. Leave **Tokens** at 10 and click **Create**.
25. Click **Run on hosts**. Leave **Run every host on this computer** ticked, then click **Run**.
26. Wait for **Run complete**. You should see `v001: 10 generated, 10 completed`.

![The Run window after the run: every phase from Build and start hosts to Replay is green, the hosts P1_Place and Monitor are stopped, the output reads v001: 10 generated, 10 completed, structure OK, and the Next steps buttons offer the replay, the charts and the analysis report.](images/ex1-run.png)

*The Run window when the run is complete.*

27. Click **Replay the recorded run**. Some tokens loop back through P1 before they reach **Terminate**.

![The replay of the recorded run: a token travels along the false arrow back to T_in_P1, and one token has reached Terminate.](images/ex1-replay.png)

*A token going round again after P1 returned `false`.*

### Look behind the run

The run was real: P1 was a host process of its own, which recorded what it did.

- **The launcher** Build wrote, `btsn.petrinet.ProjectLoader/My_P1_Workflow_BuildAndRun.xml`, names the hosts it starts:
  `<property name="hosts" value="P1"/>`.
- **P1's log** is beside it, in `P1_Place.out.txt`. Near the top: `node P1 runs [P1_Place, P1_CollectorService, P1_InitializationService]`.
- **P1's folder**, `btsn.services/target/launchers/My_P1_Workflow_BuildAndRun/P1/`, holds the rules it was sent and the database it recorded into.

### Learn more

- [The Deploy panel, Build and Run, and the analysis, step by step](Reference.md#each-step-in-detail)
- [How each host runs its place](Reference.md#inside-each-host)
- The finished copy: `btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_Tutorial_Workflow.json`

## Example 2: a claims process with a remote service

An insurer pays small claims at once and assesses the larger ones. The process has two places:

- **Triage** runs on host P1. Its service, supplied with the repository, finds a claim of up
  to 2,500 simple and a larger one complex.
- **Assess** runs on host P2, but its service does not: the host calls it on the internet,
  over REST. It accepts a claim of up to 4,000 and rejects the rest.

Claim *n* is for *n* × 500, so of the ten claims, 1–5 are paid, 6–8 accepted and 9–10 rejected.
Your computer needs internet access for this example.

![The finished Example 2 in the editor: CLAIMS feeds Triage on P1, whose T_out sends simple to Paid and complex to Assess on P2, whose T_out sends accept to Accepted and reject to Rejected. Assess is selected: its service is AssessService, called over REST.](images/ex2-model.png)

*What you will build.*

### Create the process

1. Choose **File → New → Process Definition**.
2. Choose **File → Save Process Definition As...** and save it as
   `btsn.common/ProcessDefinitionFolder/claims/Workflow/My_Claims_Workflow.json`.
   Saving it in the `claims` folder gives the process the claims services.

### Add the elements

As in Example 1: click the tool, click the canvas, type the **Label**.

3. **Event Generator**: `CLAIMS`.
4. **Transition** `T_in_Triage`, **Place** `Triage`, **Transition** `T_out_Triage`, in a row.
5. **Transition** `Paid`, below `T_out_Triage`.
6. **Transition** `T_in_Assess`, **Place** `Assess`, **Transition** `T_out_Assess`, continuing the row.
7. **Transition** `Accepted` and **Transition** `Rejected`, to the right of `T_out_Assess`.

### Connect them

Use **Arrow (drag)** for each arrow.

8. `CLAIMS` → `T_in_Triage` → `Triage` → `T_out_Triage`
9. `T_out_Triage` → `Paid`
10. `T_out_Triage` → `T_in_Assess` → `Assess` → `T_out_Assess`
11. `T_out_Assess` → `Accepted`
12. `T_out_Assess` → `Rejected`

### Set the details

13. `Triage`: **Service** `ClaimsTriageService`.
14. `Assess`: **Service** `AssessService`.
15. `T_out_Triage` and `T_out_Assess`: **Node Type** `GatewayNode`.
16. `Paid`, `Accepted` and `Rejected`: **Node Type** `TerminateNode`.
17. Set each arrow's **Guard Condition** to `DECISION_EQUAL_TO`, and its **Decision Value** and **Label** to:

    | Arrow | Value |
    |---|---|
    | `T_out_Triage` → `Paid` | `simple` |
    | `T_out_Triage` → `T_in_Assess` | `complex` |
    | `T_out_Assess` → `Accepted` | `accept` |
    | `T_out_Assess` → `Rejected` | `reject` |

18. Click **Validate**. You should see **Validation passed!**
19. Choose **File → Save Process Definition**.

### Deploy it

20. Click **Deploy**. Choose **Host node** `P1` for Triage and `P2` for Assess.
21. In Assess's row, set **Invocation Adapter** to `rest` and **Service endpoint** to
    `https://rule-propagated-service-orchestrati.vercel.app/api/claims/assess`
22. Click **Save deployment...** and accept the name it suggests.
23. Choose **File → Save Process Definition**.

![The Deploy panel: Triage on host node P1 runs ClaimsTriageService.triage; Assess on host node P2 runs AssessService.assess with the rest adapter and the service endpoint on Vercel.](images/ex2-deploy.png)

*The address in step 21 is a public copy of the service. You are free to use your own: its
code is `examples/rest/claims.js`, and any service that answers the same way will do.*

### Run it

24. Click **Build**, then **Create**.
25. Click **Run on hosts**, then **Run**. Before it starts, the editor checks that the claims service answers.
26. Wait for **Run complete**. You should see `v001: 10 generated, 10 completed`. P2 made 5 service
    calls: one for each complex claim.

![The Run window after the run: every phase is green, the hosts P1_Place, P2_Place and Monitor are stopped, P1 made 10 service calls and P2 made 5, and the output reads v001: 10 generated, 10 completed, structure OK.](images/ex2-run.png)

27. Click **Replay the recorded run**. Claims 1–5 go to **Paid**; claims 6–10 go on to Assess,
    where 6–8 are **Accepted** and 9–10 **Rejected**.

![The replay of the recorded run: five claims have been paid, one has been accepted, and claim 7 is at Assess.](images/ex2-replay.png)

*Claim 7 at Assess, whose service answered from the internet.*

### Learn more

- [Calling a service over REST, and hosting your own](ServiceTutorial.md#8-call-it-over-rest)
- [Adding a new service while you design](Reference.md#adding-services-while-designing)
- [Writing a Java service](ServiceTutorial.md)
- The finished copy: `btsn.common/ProcessDefinitionFolder/claims/Workflow/Claims_Workflow.json`
- The ready-made examples, from Petri-net models to the financial and healthcare workflows: [Run an example](Reference.md#run-an-example)

## Example 3: add a host

Every host runs the same program. How many there are is up to the infrastructure definition, so a new host is a
new row there: nothing is copied or registered. Here you add P7 and move Example 1's place onto it.

### Add the host

1. Choose **File → Open → Infrastructure Definition...** and open
   `btsn.common/InfrastructureDefinitionFolder/SingleHost.json`.
2. Click **Add host**. A new row appears: `P7`, channel `ip0`, address `192.168.1.82`, port `4009`.
   It gets 4009 because P4 and P6 already use 4007 and 4008.
3. Click **Save...** and keep `SingleHost.json`. The other examples are unchanged: none of them uses P7.

![The Infrastructure Definition window for SingleHost.json: hosts P1 to P6 on 192.168.1.82, and the new row P7 on channel ip0 with fixed base port 4009.](images/ex3-add-host.png)

### Move P1 onto P7

4. Choose **File → Open → Process Definition...** and open `My_P1_Workflow.json` from Example 1.
5. Right-click the place **P1**, then **Deploy to → P7**. The menu shows P7's address, `192.168.1.82:4009`.
   If P7 is not listed, open the process again.
6. Click **Deploy**. P1's row now shows **Host node** `P7`. Click **Save deployment...** and keep the name it suggests.
7. Choose **File → Save Process Definition**.

The place keeps its label, P1; only the host it runs on has changed.

### Run it

8. Click **Build**. The summary lists `Hosts    P7 and Monitor (each host is initialised and collected by the run)`.
   Click **Create**.
9. Click **Run on hosts**, then **Run**. The Hosts table shows `P7_Place` at `127.0.0.1:4009`.
10. Wait for **Run complete**. You should see `v001: 10 generated, 10 completed`.

![The Run window after the run on P7: every phase is green, the hosts P7_Place and Monitor are stopped, and the output reads v001: 10 generated, 10 completed.](images/ex3-run.png)

### Look behind the run

- **The launcher** now names the new host: `<property name="hosts" value="P7"/>`.
- **P7's log** is `btsn.petrinet.ProjectLoader/P7_Place.out.txt`: `node P7 runs [P7_Place, P7_CollectorService, P7_InitializationService]`.
- **P7's folder** is `btsn.services/target/launchers/My_P1_Workflow_BuildAndRun/P7/`.
- **What the run generated for P7**, in that run's own `btsn.common`:
  - its database and collection services, on ports 4306 and 4106: `RuleBase/Generated/HostAdministration.ruleml.xml`;
  - the processes that reset and collect this run's hosts: `ProcessDefinitionFolder/common/Initializers/Hosts_Initialization.json`
    and `ProcessDefinitionFolder/common/Collectors/Hosts_Collector.json`.

### Try next

- **Use both hosts.** In Example 2, deploy Assess to P7 instead of P2. The launcher then lists `P1,P7`.
- **Put P7 on another machine.** In step 2, give P7 channel `ip1` and that machine's address. Build then offers a
  host package for P7 to start there ([Run on several machines](Reference.md#7-run-on-several-machines)).
- **Move P1 back.** Deploy P1 to `P1` again and save; P7 stays available for later.

### Learn more

- [Add a host](Reference.md#add-a-host): host names (P1 to P99) and the ports kept for each host's own services
- [Choose the machines](Reference.md#choose-the-machines)

## Author

Alexander Cameron
