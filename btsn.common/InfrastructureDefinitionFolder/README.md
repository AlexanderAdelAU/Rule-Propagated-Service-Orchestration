# Physical infrastructure

All Petri-net, healthcare and financial examples share
[SingleHost.json](SingleHost.json); [MultiHost.json](MultiHost.json) spreads the
same nodes over three machines. Each defines the physical P1–P6 nodes, their
channels, address and fixed base ports. Service functions and workflow topology
are selected separately, so changing a service function leaves its network
endpoint unchanged.

| Node | Port slot 0 | Port slot 1 |
|---|---|---|
| P1 | 4001 | — |
| P2 | 4002 | — |
| P3 | 4003 | — |
| P4 | 4004 | 4007 |
| P5 | 4005 | — |
| P6 | 4006 | 4008 |

The additional slots preserve the existing healthcare operations on P4 and P6.
These are base ports; the runtime applies its existing channel offsets to obtain
transport, rule and synchronization ports. Initialization, collection and Monitor
retain their existing infrastructure operations.

## Edit and select

Choose **File → Open → Infrastructure Definition** in the workflow editor and
open `SingleHost.json`. The **Fixed Base Ports** column lists the slots in order,
starting at zero. Editing an address updates all nodes on that channel. Save the
physical definition once; every domain profile selects it.

The default `ip0` address remains `192.168.1.82`. To run any supported launcher on
one local computer, pass `-Dhost.address=127.0.0.1`. The P1 local tutorial wrapper
supplies that setting automatically. Runtime preparation applies an explicit
host override to its isolated copy of the shared definition and generates the
matching RuleML; source configuration is preserved. Service choice does not
select an address or allocate a port.

The token generators use the infrastructure the run selects (`SingleHost` unless
`-Dinfrastructure` names another). Each deployment profile selects this physical file and a [service deployment](../ServiceDeploymentFolder/README.md),
then runtime preparation generates `RuleBase/Generated/InfrastructureDeployment.ruleml.xml`.
There are no per-model network snapshots to synchronize manually.

For several machines, use [MultiHost.json](MultiHost.json) as the starting point:
one channel per machine (`ip0` is the machine that runs the launcher and Monitor,
then `ip1`, `ip2`, ...), each with that machine's address. Select it for a run with
`-Dinfrastructure=MultiHost`; the profile's own choice stays unchanged. The
`host.address` override is restricted to single-host arrangements. Each host's
initialisation and collector services follow it onto its channel, and
`ant host-packages` builds a self-contained package for every host on another
machine. See the [tutorial](../../Tutorial.md#7-run-on-several-machines).

[TwoHost.json](TwoHost.json) is the two-machine test arrangement: P1, P3 and P5 on
`ip0` (192.168.1.82, the launcher's machine) and P2, P4 and P6 on `ip1`
(192.168.1.152).

## Follow the configuration

| Location | Purpose |
|---|---|
| `InfrastructureDefinitionFolder` | Physical nodes, addresses and fixed port slots |
| [ServiceDeploymentFolder](../ServiceDeploymentFolder/README.md) | Business operations assigned to nodes and port slots |
| [BusinessServiceDefinitions](../BusinessServiceDefinitions) | Implementation classes and active service contracts |
| [ProcessDefinitionFolder](../ProcessDefinitionFolder) | Process topology, operations and guards |
| Monitor analysis folders and exported results | Observations produced by process runs |

The previous domain/model infrastructure files have been replaced by this shared
physical definition and separate service deployments. See the
[tutorial](../../Tutorial.md#2-deploy-the-process) for an editor-to-run walkthrough.
