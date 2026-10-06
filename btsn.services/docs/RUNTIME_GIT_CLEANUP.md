# One-time cleanup of runtime data in Git

Workflow runs write Derby databases, installed versioned rules and chart exports.
These are local results, not source configuration. Historical copies of them
were tracked by Git, so a successful run could fill Git Staging with changes.
The tracking cleanup removes those copies from Git and ignores future writes.
It retains source code, Eclipse project definitions, queries, business catalogues,
workflow definitions, canonical bindings and deployment rules.

Existing workspaces need to preserve their current results **before pulling the
commit that removes the tracked files**. Pull the preparation commit first;
it adds this helper without removing any runtime files. It can be pulled while
the databases and installed rules have local modifications.

1. Stop Ant launches, service hosts, analysers and chart windows.
2. Run `btsn.services/Finish_RuntimeCleanup.xml` as an Ant Build with its default
   **prepare** target. It needs the same JDK as the ordinary launchers, and no Git
   command-line installation. Wait for `BACKUP READY: <folder>` and `BUILD SUCCESSFUL`.
3. Keep runs stopped. Once the final tracking cleanup is published, pull it on
   `queue-priority-probe`. Git Staging will temporarily show deletions after
   preparation; leave them unstaged and uncommitted. The matching incoming Git
   deletion clears them during this pull.
4. Run the same XML, selecting **restore**. Wait for `RESULTS RESTORED` and
   `BUILD SUCCESSFUL`. Refresh Git Staging. The saved runtime results are now
   local files covered by `.gitignore`; ordinary BuildAndRun launches can resume.

The backup folder is beside the repository, named
`BTSN-RuntimeBackup-<timestamp>-<id>`. Every file is copied and checked with
SHA-256 before any runtime folder is moved. Directory names, empty directories
and binary contents are preserved. The helper checks Derby's exclusive lock
and refuses a database that is still open. Keep the backup folder after the
cleanup; it contains both the checked snapshot and the parked original files.
Do not run a workflow between prepare and restore, since it could create new
results at the same paths. Restore refuses to overwrite differing data.

Preparation includes the three Monitor database types, numbered-host database
folders, installed `RuleFolder.vNNN` directories, generated `chart`/`charts`
directories and captured `WorkflowRunMetadata`, wherever present in Monitor,
the six numbered projects or event generators. The normal generated runtimes
under `btsn.services/target` stay in place and remain ignored. Source files,
Eclipse settings, dependencies and Git's index are untouched by the helper.

If you need to abandon the cleanup before the final pull, select **cancel**
to restore the saved files. An interrupted preparation also retains the checked
snapshot and recovery state under `btsn.services/target/runtime-git-cleanup`.
Neither restore nor cancel deletes the external backup.

New clones after the tracking cleanup need no migration. Their ordinary
BuildAndRun launchers recreate installed rules and initialise fresh databases.
