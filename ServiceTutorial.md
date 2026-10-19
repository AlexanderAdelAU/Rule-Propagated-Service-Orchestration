# Tutorial: write your own service

This tutorial writes one service from scratch and runs it in a process: a credit
check that approves an application up to a limit and refers the rest. It is
deliberately small. The service keeps no state and runs on one host, so the
steps show only what every service needs.

![The credit check in the editor: APPLICATIONS feeds T_in_Credit, the Credit place on P1 runs CreditLimitService.assess, and T_out_Credit routes approve to Approved and refer to Referred. The Attributes panel shows the operation, what it does, and the contract assess(token) → creditDecision.](images/credit-design.png)

*The finished process. The **What it does** box and the contract come from the catalogue you write in step 2.*

The finished files are supplied, so you can compare yours with them. To build
your own alongside them, use another name throughout, for example `loan`
instead of `credit`.

**Before you start:** work through [Tutorial.md](Tutorial.md) sections 1 to 4
once. This tutorial uses the same editor steps (design, Deploy, Build, Run on
hosts) and does not repeat them in detail.

## What RPSO needs from a service

The fabric knows a service only through its **canonical binding**: the operation's
name, its named inputs and the attribute it returns. The **routing value** in that
result is what the transition guards test. Nothing else about the service matters
to the transitions, the rules or the tokens. By default a service is a Java class,
which the host finds through the catalogue and calls by reflection; steps 1 to 7
write one. [Step 8](#8-call-it-over-rest) serves the same service from Node.js over
REST instead.

| File | Step |
|---|---|
| [`CreditLimitService.java`](btsn.common/src/org/btsn/business/credit/CreditLimitService.java) | 1. Write the service |
| [`BusinessServiceDefinitions/credit/Credit.json`](btsn.common/BusinessServiceDefinitions/credit/Credit.json) | 2. Describe it in a catalogue |
| [`btsn.services/packaging.json`](btsn.services/packaging.json) | 3. Package it |
| [`ProcessDefinitionFolder/credit/Workflow/Credit_Check_Workflow.json`](btsn.common/ProcessDefinitionFolder/credit/Workflow/Credit_Check_Workflow.json) | 4. Draw the process (editor) |
| [`ServiceDeploymentFolder/credit/Credit_Check_Workflow.json`](btsn.common/ServiceDeploymentFolder/credit/Credit_Check_Workflow.json) | 5. Deploy (editor) |
| [`Credit_Check_Workflow_BuildAndRun.xml`](btsn.petrinet.ProjectLoader/Credit_Check_Workflow_BuildAndRun.xml), its schedule and deployment profile | 6. Build (editor) |

## 1. Write the service

Create `btsn.common/src/org/btsn/business/credit/CreditLimitService.java`:

```java
package org.btsn.business.credit;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

public class CreditLimitService extends BaseBusinessService {

    static final double LIMIT = 5000;

    private final String sequenceId;

    public CreditLimitService() { this(""); }

    /** The host passes the token's sequence id. */
    public CreditLimitService(String sequenceId) { this.sequenceId = sequenceId == null ? "" : sequenceId; }

    /** token -> creditDecision */
    @SuppressWarnings("unchecked")
    public String assess(String token) {
        int application = applicationNumber(sequenceId);
        double amount = numberValue(token, "amount", application * 1000.0);
        boolean approve = amount <= LIMIT;

        JSONObject result = new JSONObject();
        result.put("application", "APP-" + application);
        result.put("amount", amount);
        result.put("limit", LIMIT);
        result.put("routing_decision", routingDecision(approve ? "approve" : "refer"));
        return businessResult("creditDecision", result);
    }

    /** 1 for the first token of a version, 2 for the second, ... (token ids are base + n x 10000). */
    static int applicationNumber(String sequenceId) {
        try {
            return (int)((Long.parseLong(sequenceId.trim()) % 1_000_000L) / 10_000L) + 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
```

- **Package.** Put services in a package under `org.btsn.business`, one folder per domain.
- **Constructor.** The host creates the service with the token's sequence id. Keep a no-argument constructor as well.
- **One `String` parameter per input.** Each catalogue input arrives as a JSON string. `assess` has one input, `token`.
- **Reading values.** `BaseBusinessService` provides `textValue` and `numberValue`. They find a field anywhere in the JSON, with a default when it is missing. Here the amount comes from the token's `amount` field if it has one; otherwise application *n* asks for *n* × 1,000.
- **The result.** Return `businessResult(returnAttribute, result)`, which produces `{"creditDecision": {...}}`. The name must match the catalogue's `returnAttribute`.
- **The routing value.** Put `routingDecision("approve")` or `routingDecision("refer")` in the result. Its `routing_path` is the value the T_out guards test. Any string works, not just `true` and `false`.

## 2. Describe it in a catalogue

Create `btsn.common/BusinessServiceDefinitions/credit/Credit.json`:

```json
{
  "domain": "Credit",
  "purpose": "The service written in ServiceTutorial.md: one stateless check, routed on a string result.",
  "services": [
    {
      "service": "CreditLimitService",
      "implementationClass": "org.btsn.business.credit.CreditLimitService",
      "operation": "assess",
      "description": "Approves a credit application up to a limit of 5,000 and refers the rest. Application n asks for n x 1,000 unless the token carries an amount.",
      "returns": "A credit decision with the application, amount and limit. Routes on \"approve\" (within the limit) or \"refer\" (over it).",
      "inputs": ["token"],
      "returnAttribute": "creditDecision",
      "status": "active"
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `service`, `operation` | The names the editor offers and the process records |
| `implementationClass` | The class the host calls |
| `inputs` | The named inputs, in the order of the method's parameters |
| `returnAttribute` | The name the result is published under; the next place takes it as input |
| `description`, `returns` | Shown in the editor's **What it does** box. The editor checks require both. |

The folder name (`credit`) is the domain. Saving the process under
`ProcessDefinitionFolder/credit` in step 4 makes the editor select this catalogue.

## 3. Package it

Add the catalogue to [`btsn.services/packaging.json`](btsn.services/packaging.json),
in `catalogues` and in `catalogueDomains`:

```json
"catalogues": [
  ...
  "../btsn.common/BusinessServiceDefinitions/credit/Credit.json"
],
...
"catalogueDomains": {
  ...
  "Credit.json": "credit"
}
```

Every launcher builds the service packages before it starts the hosts, so the
service becomes `btsn.services/target/deployment/services/credit/CreditLimitService.jar`
on the next run. There is no separate build step.

## 4. Draw the process

Start (or restart) the editor so that it reads the new catalogue. Then choose **File → New → Process Definition**; **Type** stays **PetriNet**.

| Palette tool | Label | Attributes |
|---|---|---|
| Event Generator | `APPLICATIONS` | Rate (ms): `1000`; Version: `v001` |
| Transition | `T_in_Credit` | Transition Type: `T_in`; Node Type: `EdgeNode`; Buffer: `10` |
| Place | `Credit` | Service: `CreditLimitService`; Operation: `assess` |
| Transition | `T_out_Credit` | Transition Type: `T_out`; Node Type: `GatewayNode` |
| Transition | `Approved` | Transition Type: `Other`; Node Type: `TerminateNode` |
| Transition | `Referred` | Transition Type: `Other`; Node Type: `TerminateNode` |

If the **Service** list is empty, click **Choose catalogue...** and open
`BusinessServiceDefinitions/credit/Credit.json`. Selecting the place shows the
description from your catalogue and the contract `assess(token) → creditDecision`.

| Source | Target | Guard Condition | Decision Value |
|---|---|---|---|
| `APPLICATIONS` | `T_in_Credit` | | |
| `T_in_Credit` | `Credit` | | |
| `Credit` | `T_out_Credit` | | |
| `T_out_Credit` | `Approved` | `DECISION_EQUAL_TO` | `approve` |
| `T_out_Credit` | `Referred` | `DECISION_EQUAL_TO` | `refer` |

Click **Validate**, then save to
`btsn.common/ProcessDefinitionFolder/credit/Workflow/Credit_Check_Workflow.json`.

## 5. Deploy

Click **Deploy**, set `Credit`'s **Node** to `P1`, and click **Save deployment...**.
Accept the suggested `btsn.common/ServiceDeploymentFolder/credit/Credit_Check_Workflow.json`, then save the
process again. **Runs on** now reads `P1 — 192.168.1.82:4001`.

## 6. Build and run

1. Click **Build**. It writes three files:
   - the launcher, `btsn.petrinet.ProjectLoader/Credit_Check_Workflow_BuildAndRun.xml` (a domain without its own loader folder uses the Petri-net one);
   - the deployment profile, `btsn.services/deployments/credit/Credit_Check_WorkflowDeployment.json`;
   - the token schedule, `btsn.common.eventgenerators/EventTriggeringFile/Credit_Check_Workflow_BuildAndRun.csv`, one application a second.
2. Click **Run on hosts** and run the launcher. It starts P1 and Monitor, sends ten applications and collects the records.

Expect ten workflows generated and ten completed. Applications 1 to 5 ask for 1,000
to 5,000 and are approved; applications 6 to 10 ask for 6,000 to 10,000 and are
referred. The replay shows each token leaving the Credit place by its route.

## 7. Give it data

A token can carry data to the service. Set the launcher's `eg1.data` property, or
pass it when running the launcher yourself:

```sh
ant -f btsn.petrinet.ProjectLoader/Credit_Check_Workflow_BuildAndRun.xml -Dhost.address=127.0.0.1 -Deg1.data=amount=4500
```

Every application now asks for 4,500, so all ten are approved. `eg1.data` takes
`name=value` pairs separated by commas; the service reads them with `numberValue`
or `textValue`.

## 8. Call it over REST

The host can call a service over HTTP instead of in-process. The service can then
be written in any language and run anywhere the host can reach. The catalogue
contract stays the same: only the placement changes, in the Deploy panel.

The same credit check, in Node.js, is supplied in [`examples/rest`](examples/rest). It
needs no packages. [`credit.js`](examples/rest/credit.js) holds the logic,
[`credit-service.js`](examples/rest/credit-service.js) serves it, and
[`api/credit/assess.js`](examples/rest/api/credit/assess.js) is the same service as a
Vercel function:

```js
function assess(call, servedBy) {
  const application = applicationNumber(call.sequenceId);
  const given = Number(find(call.inputs.token, 'amount'));
  const amount = Number.isFinite(given) ? given : application * 1000;
  const decision = amount <= LIMIT ? 'approve' : 'refer';
  return {
    creditDecision: {
      application: 'APP-' + application, amount: amount, limit: LIMIT, served_by: servedBy,
      routing_decision: { routing_path: decision }
    }
  };
}
```

**What the host sends and expects.** For each call the host POSTs a JSON body to the endpoint:

```json
{"service": "CreditLimitService", "operation": "assess", "sequenceId": "1060000", "version": "v001",
 "inputs": {"token": {"tokenId": "1060000", "data": {}}}}
```

and expects, with HTTP 200, the body a Java service would return: the result under
the catalogue's `returnAttribute`, with its `routing_decision.routing_path`:

```json
{"creditDecision": {"application": "APP-7", "amount": 7000, "limit": 5000, "routing_decision": {"routing_path": "refer"}}}
```

`inputs` holds one entry per catalogue input, by name. `sequenceId` is what a Java
service receives in its constructor.

**Try it.** The supplied
[`Credit_Check_Rest_Workflow.json`](btsn.common/ProcessDefinitionFolder/credit/Workflow/Credit_Check_Rest_Workflow.json)
is the same process, deployed this way.

1. Start the service: `node examples/rest/credit-service.js`. It listens on `http://127.0.0.1:8080/credit/assess`.
2. Open `credit/Workflow/Credit_Check_Rest_Workflow.json` and click **Deploy**. In `Credit`'s row:
   - **Invocation Adapter**: `rest`;
   - **Endpoint**: `http://127.0.0.1:8080/credit/assess`.

   Save the deployment (the editor suggests `ServiceDeploymentFolder/credit/Credit_Check_Rest_Workflow.json`), then the process. Selecting the place now shows **Runs on** `P1 — ...`, calling the endpoint (REST).
3. Click **Build**, then **Run on hosts**.

The Node console prints each application and its decision, and the run gives the
same result as before: ten workflows completed, five approved and five referred.

- **Where the endpoint is called from.** The host (P1 here) makes the call, so the
  address must be reachable from P1's machine. `127.0.0.1` works when the service runs
  on that machine.
- **When it fails.** If the endpoint cannot be reached, answers an error, or returns a
  body without the return attribute, the call fails as a Java service that throws
  would. The host's log names the endpoint; with the service stopped, a run reports ten
  workflows generated and none completed.
- **Timeout.** A call waits up to 10 seconds. To change it, add `"timeoutMs": 5000` to
  the placement in the service deployment file.
- **No Java class at all.** A service that only exists behind an endpoint can leave
  `implementationClass` out of its catalogue entry. It must then be deployed with the
  `rest` adapter, and its catalogue left out of `packaging.json`, since there is nothing
  to package.
- **Boolean services** keep the `boolean-token` adapter.

### Host it on the internet

To call the service across a real network, host it on a public platform and use its
`https://` address as the Endpoint. You need your own account; the hosts need outbound
internet access. Both of these have free plans:

| | Render (a Node server) | Vercel (a function) |
|---|---|---|
| **Create** | New **Web Service** from your GitHub repository | New **Project** from your GitHub repository |
| **Root directory** | `examples/rest` | `examples/rest` |
| **Start** | Build command: `npm install`; start command: `npm start` | Nothing to set; Vercel finds `api/` |
| **Endpoint** | `https://<your-service>.onrender.com/credit/assess` | `https://<your-project>.vercel.app/api/credit/assess` |
| **Check it** | Open the address without `/credit/assess`: it answers *Credit service* | Open the endpoint in a browser: it answers *POST a call to this address* |

Then set the placement's **Endpoint** to that address in the Deploy panel, save,
**Build** and **Run on hosts**. The result shows which platform answered: `served_by`
is `node` or `vercel` in each credit decision.

- **Accounts.** Render asks for a credit card when you create a service, even on the free
  plan. Vercel's free Hobby plan does not.
- **Cold starts.** A free Render service sleeps after 15 minutes without calls, and
  its first call can take longer than the 10-second timeout. Open the check address
  just before a run, or add `"timeoutMs": 60000` to the placement.
- **Network time counts.** Monitor measures the call from the host, so service times
  now include the round trip to the platform.

## Going further

- **State, several hosts and loops:** the inventory example in
  [Tutorial.md, Add your own service](Tutorial.md#6-add-your-own-service) keeps a
  stock count in its host and sends orders between two hosts and back.
- **A second place:** give the next place an input named after this one's
  `returnAttribute` (here `creditDecision`), and it receives this result.
- **Several machines:** see [Tutorial.md, Run on several machines](Tutorial.md#7-run-on-several-machines).
