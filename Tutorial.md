# From an idea to a measured run

We will walk you through two examples. Both are built graphically in the editor.

- **Example 1** runs a standard service that returns true or false.
- **Example 2** builds a simple claims process that calls a remote service over REST.

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

24. Click **Build**. Leave **Tokens** at 10 and click **Create**.
25. Click **Run on hosts**. Tick **Run every host on this computer**, then click **Run**.
26. Wait for **Run complete**. You should see `v001: 10 generated, 10 completed`.
27. Click **Replay the recorded run**. Some tokens loop back through P1 before they reach **Terminate**.

![The replay of the recorded run: a token travels along the false arrow back to T_in_P1, and one token has reached Terminate.](images/ex1-replay.png)

*A token going round again after P1 returned `false`.*

### Learn more

- [The Deploy panel, Build and Run, and the analysis, step by step](Reference.md#each-step-in-detail)
- [How each host runs its place](Reference.md#inside-each-host)
- The finished copy: `btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_Tutorial_Workflow.json`

## Example 2: a claims process with a remote service

*Being written.*

## Author

Alexander Cameron
