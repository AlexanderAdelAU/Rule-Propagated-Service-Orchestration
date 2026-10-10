# Physical infrastructure

All Petri-net, healthcare and financial examples share
[SingleHost.json](SingleHost.json). It defines the physical P1–P6 nodes, their
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

The token generators use `-infrastructure SingleHost`. Each deployment profile
selects this physical file and a [service deployment](../ServiceDeploymentFolder/README.md),
then runtime preparation generates `RuleBase/Generated/InfrastructureDeployment.ruleml.xml`.
There are no per-model network snapshots to synchronize manually.

For a distributed arrangement, create another physical definition with the
appropriate node/channel/address mapping and fixed ports, then select it in the
deployment profile and launcher. The `host.address` override is restricted to
single-host arrangements. Review the existing administrative service placement
when distributing nodes; those operations currently use their existing channels.

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
