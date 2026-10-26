# One physical fork, two philosophers: child-token rendezvous experiment

**Status:** Design specification only. Not yet an executable or runtime-verified ProcessEditor workflow.

**Constraint:** No changes to the RPSO host, token identifier format, join semantics, or ProcessEditor.
This deliberately does **not** reintroduce the closed Dining Philosophers PR #112.

## Why children matter

A physical fork **F1** has one unique logical identity, represented by an RPSO token *family*:
`aaaa` (numerically, e.g. `1000000`). Its children may be
`aaaa01`, `aaaa02`, ... `aaaa05`.
These are **different children of the same physical fork family**, not five physical forks.

In the current `ServiceThread.mapFromSequenceID`, the family key is
`sequenceId - (sequenceId % 100)`. Thus both `1000001` and `1000005`
have join key `1000000`. A join need not use consecutive child suffixes:
with the corresponding canonical input names it may join child 01 and child 05.
However, being in the same family alone does not guarantee the join will execute:
the requisite routed attributes must actually arrive, and all other active siblings
and join-buffer state must be accounted for.

**Do not confuse a child token with another copy of the physical fork.**
Children represent concurrent pieces of one logical workflow/resource protocol.

## First experiment: exclusive grant and reunion of F1

Use just **one** physical fork F1 and **two** possible recipients P1 and P2.
The chooser makes one ownership decision *before splitting the fork*.
Both of the two emitted children follow P1's two paths, or both follow P2's.
They meet at that philosopher's JoinNode. When the join completes,
control returns to the chooser, making F1 available for another handoff.

```text
                 [F1 AVAILABLE: family aaaa]
                              |
                   [ChooseOwner: XOR]
                         /          \
                      grant P1     grant P2
                        |             |
                    [ForkNode]     [ForkNode]
                     /     \        /      \
                aaaa01  aaaa02  aaaa01   aaaa02
                    \    /          \     /
                 [Join P1]         [Join P2]
                      |                |
                 [P1 releases]     [P2 releases]
                      \                /
                         [F1 AVAILABLE]
```

The two arms of a particular grant *may* contain intermediate places,
service calls, wait states, and different delays. The join must wait for all
of the grant's prescribed child contributions before releasing the family.

Use existing **XorNode, ForkNode, JoinNode, EdgeNode**, and
ordinary service Places (e.g. `StochasticService` for choosing a recipient
and `AlwaysTrueService` for deterministic paths). No new resource type.

### Intended state invariant

Exactly one of the following is true for F1 at any logical instant:

* `AVAILABLE`: ready to make a single new grant;
* `GRANTED_P1`: F1 has been granted to P1; its children have not fully reunited;
* `GRANTED_P2`: F1 has been granted to P2; its children have not fully reunited.

The two children of a grant do **not** count as two independently owned forks.

**Important:** This is a *controlled exclusive-handoff* experiment, **not yet**
Workcraft's two independent philosophers simultaneously competing for the same
`fork_free` place, and not yet a true Dining Philosophers deadlock test.

## Why start with 01 and 02, despite the 01/05 capability?

A two-output ForkNode naturally creates two children of a root token,
typically `1000001` and `1000002`. Use this simplest case to validate a
complete physical fork lifecycle first.

A direct experiment with `1000001` and `1000005` is valuable as a
**second, focused join test**. It requires an actual path producing both child
numbers (for example a suitable five-way fan-out) and a JoinNode with matching
canonical attribute names (e.g. `token_branch1` and `token_branch5`).
A two-output root ForkNode does **not** create 01 and 05 merely because
the family permits them. Children 02, 03, and 04 must not be ignored:
their presence, cleanup, and effect on per-family join state must be specified.

## Relevant unchanged-RPSO contracts to verify

1. **Family-matching:** `mapFromSequenceID(1000001) == mapFromSequenceID(1000005) == 1000000`.
2. **Canonical binding:** A two-input JoinNode must see both prescribed attribute
   names, not just any two attributes of the same family. Current
   `ServiceThread.isJoinComplete` checks **count first**; missing names are
   only discovered later when populating input arguments. Do not assume an
   arbitrary mixed-attribute arrival will work.
3. **Join bookkeeping:** `argValPriorityMap`, `joinExpectedCounts`, and
   `joinContributions` are keyed by family/join ID. Establish that the
   intermediate paths do not interfere with a pending join, especially when
   sharing a host and processing siblings of the same family.
4. **Post-join identity:** The current join continues with the **lowest
   participating child ID**; the subsequent ForkNode creates child IDs from
   that continuing ID. Check the emitted ID/attribute names on **every cycle**.
5. **Queue scheduling:** `EventReactor` stores scheduled packets in a
   `TreeMap<Long,String>` keyed by a priority derived from token ID;
   collisions would overwrite entries. Verify no two live/scheduled packets
   with equal priority keys occur in this experiment.
6. **Timeout:** A join waiting for an unavailable second child can expire.
   Timeout or inactivity is **not**, by itself, a formal Petri-net deadlock.

## Acceptance tests, with no runtime changes

- One input child arrives: the selected philosopher's join waits.
- The second prescribed child arrives: the selected join completes exactly once.
- No child arrives at the nonselected philosopher's join for that grant.
- The two child tokens do not give two independent philosophers permission to use F1.
- After reunion/release, one further F1 grant can be made, with no active ID
  collisions, dropped packets, or invalid canonical bindings.
- Repeat many cycles and check exactly one owner per cycle, one completed
  reunion per grant, and no leaked child contributions.
- Only after that passes, test nonconsecutive child reunion (01 and 05)
  with explicit accounting for other siblings.

## Next step toward genuine Dining Philosophers

Add F2, independently owned, and require P1 or P2 to hold **both**
required physical fork resources before eating. Note that standard RPSO
join buffers use the **parent family** as their join key. A JoinNode for
`aaaa` children does **not automatically join** with `bbbb` children.
A faithful two-resource acquisition protocol must be demonstrated, not
assumed. If the unchanged runtime cannot express that cross-family
synchronisation, record the limitation rather than silently change the runtime
or call a family-only rendezvous classical Dining Philosophers.

## Source references

- `btsn.rpso.host/src/org/btsn/handlers/ServiceThread.java`
  (`mapFromSequenceID`, `isJoinComplete`, `processCompleteJoinSafely`)
- `btsn.rpso.host/src/org/btsn/handlers/TransitionHandler.java`
  (`processForkType`, XOR and Gateway fan-out)
- `btsn.rpso.host/src/org/btsn/handlers/EventReactor.java`
  (`costKeyTokenMap`, `putScheduledToken`)
- `btsn.common/ProcessDefinitionFolder/petrinet/Workflow/Rendezvous_Workflow.json`
  (existing fork/join topology example)

## Experimental ProcessEditor workflow included on this branch

The branch also contains `btsn.common/ProcessDefinitionFolder/petrinet/Workflow/SingleFork_ExclusiveHandoff_Workflow.json`, an **unverified test model**, with matching
service deployment, launcher profile, build/run XML, and trigger CSV. It follows
the controlled-exclusive-handoff diagram above and reuses existing node types.

Run from `btsn.petrinet.ProjectLoader`:

```sh
ant -f SingleFork_ExclusiveHandoff_Workflow_BuildAndRun.xml -Dhost.address=127.0.0.1
```

**Do not treat a successful build as a behavioural proof.** Inspect the log and monitor for:
child IDs and attribute names at each ForkNode/JoinNode, correct two-input
waiting, exactly one granted philosopher at a time, no overwrite in
`EventReactor.costKeyTokenMap`, and no family-map interference at upstream
EdgeNode services. The workflow may expose an existing runtime limitation,
which is useful evidence without requiring any runtime changes.
