# Recorded service timings

`SwingGanttChart_WithLatency_v1d` opens the workflow elapsed view by default: one row per observed workflow root. A complete Stage 5 dataset therefore shows 55 workflows (15 v001 and 40 v002), regardless of how many services each workflow invokes.

Choose **View → Recorded Service Invocations** to inspect individual service executions. In that view, each bar is one recorded invocation, ordered by its arrival time. Repeated visits by a circulating token remain separate. Service time and queue wait are independent recorded measurements, not workflow completion times.

The default input is the existing collected `ServiceAnalysisDataBase` in the working directory. To inspect a stopped host directly, supply its local database directory as a program argument:

```text
-db "../btsn.services/target/launchers/TrafficLight_BuildAndRun/btsn.rpso.places.p1/ServiceAnalysisDataBase"
```

Repeat `-db` for P2 through P6 to combine the stopped hosts, then choose **View → Recorded Service Invocations**. Paths are relative to the chart's working directory, or can be absolute. Stop the hosts before opening their embedded Derby databases, so their database locks are released.

Local analysis uses `SERVICEMEASUREMENTS`: queue wait is invocation time minus arrival time; service time is publish time minus invocation time. It requires no running Monitor, Monitor acknowledgement, `PROCESSMEASUREMENTS`, workflow generation record, or termination record. Existing collected `SERVICECONTRIBUTION` records are supported too.

Exact repeat observations are counted once. Conflicting, missing or negative timings remain unavailable; valid zero durations remain zero. A partly recorded invocation can still show its recorded queue wait. Missing business/process labels do not suppress measurements.

Workflow elapsed time requires recorded start/completion intervals. Open workflows still retain their measured queue observations. The separate service invocation view remains available for circulating tokens without any generation or completion record.
