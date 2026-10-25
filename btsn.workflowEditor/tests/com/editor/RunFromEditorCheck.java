package com.editor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Drives the editor's Run (LauncherRun) with a stand-in for Ant: it prints the launcher's phase lines,
 * starts a long-lived child "host" like the real launcher does, reports completion and keeps running.
 * Checks completion is seen, the whole process tree is stopped, the analysis is saved, and Stop works.
 */
public final class RunFromEditorCheck {
    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }

    private static void run(String[] args) throws Exception {
        File repository = new File(args[0]).getCanonicalFile();
        // Steps: commitments in initialisation (v999) are not the workflow's; those after its rules are sent are.
        check(LauncherRun.phaseOf("=== PHASE 2: WORKFLOW EXECUTION ===", LauncherRun.Phase.INITIALISE) == LauncherRun.Phase.DEPLOY, "Phase 2 starts rule deployment");
        check(LauncherRun.phaseOf("DEBUG [main] (?:?) - Commitment confirmed for P2_InitializationService:purgeAndInitialize in 100ms", LauncherRun.Phase.INITIALISE) == null, "Initialisation commitment taken as the workflow's");
        check(LauncherRun.phaseOf("DEBUG [main] (?:?) - Commitment confirmed for P2_Place:processToken in 101ms", LauncherRun.Phase.DEPLOY) == LauncherRun.Phase.COMMIT, "Workflow commitment not seen");
        check(LauncherRun.phaseOf("Deploying petrinet/Workflow/X (v002) for EG6...", LauncherRun.Phase.COMMIT) == LauncherRun.Phase.DEPLOY, "Second version's deployment not seen");
        check(LauncherRun.phaseOf("Firing 10 tokens from EG11 (v001) to P2_Place.processToken...", LauncherRun.Phase.COMMIT) == LauncherRun.Phase.FIRE, "Firing not seen");
        List<File> launchers = LauncherRun.launchersFor(repository, "petrinet/Workflow/P2_Tutorial_Workflow");
        check(!launchers.isEmpty() && launchers.get(0).getName().equals("P2_Tutorial_Workflow_BuildAndRun.xml"), "P2 launcher not found first: " + launchers);
        // The closing summary: per version, generated and completed, flagged when some did not complete.
        String single = new String(Files.readAllBytes(repository.toPath().resolve("btsn.workflowEditor/tests/fixtures/Analysis_P2_single_generator.txt")), StandardCharsets.UTF_8);
        List<String> summary = LauncherRun.summarise(single);
        check(summary.get(0).equals("=== RUN COMPLETE ===") && summary.contains("v001: 10 generated, 10 completed, structure OK, latency 152 to 885 ms (median 260 ms)"), "Summary: " + summary);
        String twoVersions = "WORKFLOW BASE: 1000000 (v001)\n   Generated workflows:       10\n   Completed workflows:       10\n   Structural result:         [OK]\n"
            + "WORKFLOW BASE: 2000000 (v002)\n   Generated workflows:       10\n   Completed workflows:       8\n";
        List<String> partial = LauncherRun.summarise(twoVersions);
        check(partial.contains("v001: 10 generated, 10 completed, structure OK") && partial.contains("v002: 10 generated, 8 completed  <-- 2 did not complete"), "Partial summary: " + partial);
        // Tokens that failed at a place are listed under their version, with the reason.
        String failing = "WORKFLOW BASE: 1000000 (v001)\n   Generated workflows:       10\n   Completed workflows:       5\n   Failed workflows:          5\n"
            + "     5 failed at CreditLimitService (P1_Place): REST service CreditLimitService.assess at http://127.0.0.1:8080/credit/assess could not be reached: java.net.ConnectException\n";
        List<String> failedSummary = LauncherRun.summarise(failing);
        check(failedSummary.equals(Arrays.asList("=== RUN COMPLETE ===", "v001: 10 generated, 5 completed  <-- 5 did not complete",
            "    5 failed at CreditLimitService (P1_Place): REST service CreditLimitService.assess at http://127.0.0.1:8080/credit/assess could not be reached: java.net.ConnectException")),
            "Failures not summarised: " + failedSummary);
        // Hosts: the launcher's hosts and endpoints, then live state from each host's own log.
        File p2Launcher = launchers.get(0);
        RunHosts here = RunHosts.forLauncher(p2Launcher, repository, true);
        check(here.hosts.size() == 2 && here.hosts.get(0).name.equals("P2_Place") && here.hosts.get(0).endpoint.equals("127.0.0.1:4002") && here.hosts.get(1).name.equals("Monitor"), "Hosts: " + here.describe());
        check(RunHosts.forLauncher(p2Launcher, repository, false).hosts.get(0).endpoint.matches("\\d+\\.\\d+\\.\\d+\\.\\d+:4002"), "Configured address not used");
        // Machines: the editor's choice of infrastructure gives the nodes their addresses and is passed to Ant.
        File processes = new File(repository, "btsn.common/ProcessDefinitionFolder");
        List<String> machines = ServiceRegistry.infrastructures(processes);
        check(machines.get(0).equals("SingleHost") && machines.contains("TwoHost") && machines.contains("MultiHost"), "Machines: " + machines);
        check(!ServiceRegistry.severalMachines(ServiceRegistry.infrastructureNodes(processes, "SingleHost"))
            && ServiceRegistry.severalMachines(ServiceRegistry.infrastructureNodes(processes, "TwoHost")), "Several machines not recognised");
        check(ServiceRegistry.selectedInfrastructure().equals("SingleHost"), "The checks run on SingleHost");
        check(RunHosts.forLauncher(p2Launcher, repository, false, "TwoHost").hosts.get(0).endpoint.equals("192.168.1.152:4002"), "P2 not on the TwoHost address");
        check(RunWindow.describeMachines("TwoHost", ServiceRegistry.infrastructureNodes(processes, "TwoHost"))
            .equals("TwoHost: P1, P3, P5 on 192.168.1.82; P2, P4, P6 on 192.168.1.152"), "Machines description");
        // The whole console is saved with the build output, not in the analysis folder, named after the launcher that ran.
        File runLog = RunWindow.runLogFile(repository,
            new File(repository, "btsn.petrinet.ProjectLoader/P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange.xml"));
        check(runLog.equals(new File(repository, "btsn.services/target/run-logs/Run_P1_P2_P3_P4_ForkRebalance_Workflow_LiveChange.txt")),
            "Run log location: " + runLog);
        LauncherRun plain = new LauncherRun(p2Launcher, repository, new File(repository, "unused.txt"), false, "", null);
        check(plain.infrastructureArgs().equals(List.of("-Dinfrastructure=SingleHost")) && plain.infrastructure("TwoHost").infrastructureArgs().equals(List.of("-Dinfrastructure=TwoHost")), "Ant option for the machines");
        // Build packages the hosts on other machines: for the inventory example on TwoHost, P2 for 192.168.1.152.
        File inventory = new File(repository, "btsn.common/ProcessDefinitionFolder/inventory/Workflow/Inventory_Workflow.json");
        BuildAndRunGenerator.Plan[] inventoryPlan = new BuildAndRunGenerator.Plan[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                Canvas canvas = new Canvas(); canvas.setDefinitionLocation(inventory);
                canvas.loadFromJSON(new String(Files.readAllBytes(inventory.toPath()), StandardCharsets.UTF_8));
                inventoryPlan[0] = BuildAndRunGenerator.plan(canvas, inventory);
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        check(BuildAndRunGenerator.packagedHosts(inventoryPlan[0]).isEmpty(), "SingleHost needs no host packages");
        System.setProperty("rpso.infrastructure", "TwoHost");
        try {
            check(BuildAndRunGenerator.packagedHosts(inventoryPlan[0]).equals(List.of("P2 for 192.168.1.152")),
                "TwoHost packages: " + BuildAndRunGenerator.packagedHosts(inventoryPlan[0]));
            check(BuildAndRunGenerator.summary(inventoryPlan[0], inventoryPlan[0].defaults).stream().anyMatch(l -> l.startsWith("Package  P2 for 192.168.1.152")),
                "Build's summary does not list the host package");
        } finally {
            System.setProperty("rpso.infrastructure", "SingleHost");
        }
        check(LauncherRun.hostPackagesFolder(repository, new File("Inventory_Workflow_BuildAndRun.xml")).getPath()
            .replace('\\', '/').endsWith("btsn.services/target/host-packages/Inventory_Workflow_BuildAndRun"), "Host package folder");
        // Opening or saving a definition in InfrastructureDefinitionFolder makes it the editor's machines; elsewhere it does not.
        int[] told = {0};
        Runnable listener = () -> told[0]++;
        ServiceRegistry.addInfrastructureListener(listener);
        String before = System.clearProperty("rpso.infrastructure");
        String savedChoice = java.util.prefs.Preferences.userNodeForPackage(ServiceRegistry.class).get("infrastructure", null);
        try {
            ServiceRegistry.selectInfrastructure("SingleHost");
            check("TwoHost".equals(ServiceRegistry.useInfrastructureFile(new File(repository, "btsn.common/InfrastructureDefinitionFolder/TwoHost.json")))
                && ServiceRegistry.selectedInfrastructure().equals("TwoHost") && told[0] >= 1, "Opening TwoHost.json did not choose it");
            File elsewhere = Files.createTempFile("Elsewhere", ".json").toFile(); elsewhere.deleteOnExit();
            check(ServiceRegistry.useInfrastructureFile(elsewhere) == null && ServiceRegistry.selectedInfrastructure().equals("TwoHost"), "A definition outside the folder was chosen");
        } finally {
            if (savedChoice == null) java.util.prefs.Preferences.userNodeForPackage(ServiceRegistry.class).remove("infrastructure");
            else java.util.prefs.Preferences.userNodeForPackage(ServiceRegistry.class).put("infrastructure", savedChoice);
            if (before != null) System.setProperty("rpso.infrastructure", before);
            ServiceRegistry.removeInfrastructureListener(listener);
        }
        Path logDir = Files.createTempDirectory("run-hosts-");
        Path fakeLauncher = logDir.resolve("X_BuildAndRun.xml");
        Files.write(fakeLauncher, "<property name=\"run.p2\" value=\"true\"/>".getBytes(StandardCharsets.UTF_8));
        Path p2Log = logDir.resolve("P2_Place.out.txt");
        Files.write(p2Log, "old run\n".getBytes(StandardCharsets.UTF_8));
        p2Log.toFile().setLastModified(System.currentTimeMillis() - 60_000);
        RunHosts live = RunHosts.forLauncher(fakeLauncher.toFile(), repository, true);
        live.started(System.currentTimeMillis());
        live.refresh();
        check(live.hosts.get(0).state.equals("waiting"), "An earlier run's log was read: " + live.hosts.get(0).state);
        Files.write(p2Log, (" INFO [main] (?:?) - Host process: 4242 on lab-pc\n Service Handler is listening on UDP port: 14002\n"
            + " INFO - SERVICE COMPLETE: org.btsn.places.P2_Place.processToken completed in 3ms\n INFO - SERVICE COMPLE").getBytes(StandardCharsets.UTF_8));
        live.refresh();
        RunHosts.Host p2 = live.hosts.get(0);
        check(p2.state.equals("running") && p2.process.equals("process 4242 on lab-pc") && p2.calls == 1, "Live host: " + p2.state + " / " + p2.process + " / " + p2.calls);
        Files.write(p2Log, ("TE: org.btsn.places.P2_Place.processToken completed in 4ms\n INFO - SERVICE COMPLETE: org.btsn.places.P2_CollectorService.collectAllData completed\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        live.refresh();
        check(p2.calls == 2, "A call split across refreshes, or an admin call, counted wrongly: " + p2.calls);
        live.stopped();
        check(p2.state.equals("stopped") && live.describe().startsWith("P2_Place at 127.0.0.1:4002 (process 4242 on lab-pc, 2 service calls)"), "Described: " + live.describe());
        RunHosts away = RunHosts.forLauncher(fakeLauncher.toFile(), repository, false);
        away.started(System.currentTimeMillis());
        away.launcherLine("  [p2] Skipping - running on remote host");
        // Commitments, per host: admin services commit for their host.
        RunHosts committing = RunHosts.forLauncher(fakeLauncher.toFile(), repository, true);
        committing.started(System.currentTimeMillis());
        for (String line : new String[] {
                " INFO [CommitmentListener-v999] (?:?) - [OK] UDP commitment confirmation received: v999:1",
                "DEBUG [main] (?:?) - Commitment confirmed for P2_InitializationService:purgeAndInitialize in 100ms",
                " INFO [CommitmentListener-v999] (?:?) - [OK] UDP commitment confirmation received: v999:2",
                "DEBUG [main] (?:?) - Commitment confirmed for Monitor_InitializationService:purgeAndInitialize in 101ms",
                " INFO [CommitmentListener-v001] (?:?) - [OK] UDP commitment confirmation received: v001:1",
                "DEBUG [main] (?:?) - Commitment confirmed for P2_Place:processToken in 101ms",
                " INFO [CommitmentListener-v002] (?:?) - [OK] UDP commitment confirmation received: v002:1",
                "DEBUG [main] (?:?) - Commitment confirmed for P2_Place:processToken in 101ms"})
            committing.launcherLine(line);
        check(committing.hosts.get(0).committed.equals(Arrays.asList("v999", "v001", "v002")) && committing.hosts.get(1).committed.equals(Arrays.asList("v999")), "Commitments: " + committing.hosts.get(0).committed + " / " + committing.hosts.get(1).committed);
        check(away.hosts.get(0).remote && away.hosts.get(0).state.startsWith("remote host"), "Remote host not shown");

        // A project reached through ".." (e.g. a process opened as ...\\btsn.workflowEditor\\..\\btsn.common\\...) gives real paths.
        File dotted = new File(repository, "btsn.workflowEditor/..");
        List<File> viaDots = LauncherRun.launchersFor(dotted, "petrinet/Workflow/P2_Tutorial_Workflow");
        check(!viaDots.isEmpty() && !viaDots.get(0).getPath().contains("..") && viaDots.get(0).equals(launchers.get(0)), "Launcher path keeps '..': " + viaDots);
        check(!LauncherRun.analysisFileFor(dotted, "petrinet/Workflow/P2_Tutorial_Workflow").getPath().contains(".."), "Analysis path keeps '..'");
        check(LauncherRun.analysisFileFor(repository, "petrinet/Workflow/P2_Tutorial_Workflow").getPath().replace('\\', '/')
            .endsWith("btsn.common/AnalysisFolder/PetriNet/Analysis_P2_Tutorial_Workflow.txt"), "Analysis file location");
        // Ant is found from its folder or anything inside it; a launcher in the Ant box is refused.
        File antHome = LauncherRun.antHome("");
        check(new File(antHome, "lib/ant-launcher.jar").isFile(), "Ant not found automatically: " + antHome);
        check(antHome.equals(LauncherRun.antHome(new File(antHome, "bin").getPath())) && antHome.equals(LauncherRun.antHome(new File(antHome, "lib/ant-launcher.jar").getPath())), "Ant not found from inside its folder");
        try { LauncherRun.antHome(new File(repository, "btsn.petrinet.ProjectLoader/P2_Tutorial_Workflow_BuildAndRun.xml").getPath()); check(false, "Launcher accepted as Ant"); }
        catch (java.io.IOException expected) { check(expected.getMessage().contains("holds a launcher"), "Wrong message: " + expected.getMessage()); }
        check(LauncherRun.antLaunch(antHome).contains("org.apache.tools.ant.launch.Launcher"), "Ant not run through its launcher class");
        // A packaged Ant (as on Ubuntu): /usr/bin/ant links into Ant's folder, whose lib/ant-launcher.jar links to a shared jar.
        if (!LauncherRun.windows()) {
            Path pkg = Files.createTempDirectory("packaged-ant-");
            Path shared = Files.createDirectories(pkg.resolve("usr/share/java"));
            Files.write(shared.resolve("ant-launcher-1.10.14.jar"), new byte[0]);
            Path antDir = pkg.resolve("usr/share/ant");
            Files.createDirectories(antDir.resolve("lib")); Files.createDirectories(antDir.resolve("bin"));
            Files.createSymbolicLink(antDir.resolve("lib/ant-launcher.jar"), shared.resolve("ant-launcher-1.10.14.jar"));
            Files.write(antDir.resolve("bin/ant"), new byte[0]);
            Path usrBin = Files.createDirectories(pkg.resolve("usr/bin"));
            Files.createSymbolicLink(usrBin.resolve("ant"), antDir.resolve("bin/ant"));
            File expected = antDir.toFile().getCanonicalFile();
            check(expected.equals(LauncherRun.antHome(usrBin.resolve("ant").toString()).getCanonicalFile()), "Packaged Ant not found from /usr/bin/ant");
            check(expected.equals(LauncherRun.antHome(antDir.resolve("lib/ant-launcher.jar").toString()).getCanonicalFile()), "Packaged Ant not found from its linked launcher jar");
        }
        // Eclipse's own Ant, found from the Java Eclipse ships inside its plugins folder.
        Path eclipse = Files.createTempDirectory("eclipse-");
        for (String version : new String[] {"org.apache.ant_1.10.12.v20211102-1452", "org.apache.ant_1.10.14.v20230922-1200"}) {
            Path lib = Files.createDirectories(eclipse.resolve("plugins/" + version + "/lib"));
            Files.write(lib.resolve("ant-launcher.jar"), new byte[0]);
        }
        File eclipseJava = eclipse.resolve("plugins/org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_21/jre/bin/javaw.exe").toFile();
        File found = LauncherRun.eclipseAnt(Collections.singletonList(eclipseJava));
        check(found != null && found.getName().startsWith("org.apache.ant_1.10.14"), "Eclipse's Ant not found (newest): " + found);
        if (LauncherRun.windows()) { System.out.println("PASS (Windows: process checks skipped)"); return; }

        Path scratch = Files.createTempDirectory("run-from-editor-");
        Path loader = Files.createDirectories(scratch.resolve("btsn.petrinet.ProjectLoader"));
        Path launcher = loader.resolve("Demo_BuildAndRun.xml");
        Files.write(launcher, "<project/>".getBytes(StandardCharsets.UTF_8));
        Path hostPid = scratch.resolve("host.pid");
        // A stand-in Ant folder: lib/ant-launcher.jar holds a Launcher that prints the launcher's lines,
        // starts a long-lived child "host" (as the real launcher does) and keeps running.
        Path ant = fakeAnt(scratch);
        File analysis = scratch.resolve("btsn.common/AnalysisFolder/PetriNet/Analysis_Demo.txt").toFile();

        // A complete run: completion seen, hosts stopped, analysis saved.
        Recorder complete = new Recorder();
        // Reached through "..", as when the process was opened through a relative folder.
        File dottedLauncher = scratch.resolve("btsn.petrinet.ProjectLoader/../btsn.petrinet.ProjectLoader/Demo_BuildAndRun.xml").toFile();
        LauncherRun run = new LauncherRun(dottedLauncher, scratch.resolve("btsn.petrinet.ProjectLoader/..").toFile(), analysis, true, ant.toString(), complete);
        run.settleMillis = 300; run.pollMillis = 100;
        run.start();
        check(complete.done.await(60, TimeUnit.SECONDS), "Run did not finish");
        check(complete.outcome == LauncherRun.Phase.DONE && analysis.equals(complete.analysis), "Run outcome " + complete.outcome + ": " + complete.message + "\n" + complete.lines);
        check(complete.phases.containsAll(Arrays.asList(LauncherRun.Phase.BUILD, LauncherRun.Phase.INITIALISE, LauncherRun.Phase.DEPLOY, LauncherRun.Phase.COMMIT, LauncherRun.Phase.FIRE,
            LauncherRun.Phase.COLLECT, LauncherRun.Phase.SETTLE, LauncherRun.Phase.ANALYSE, LauncherRun.Phase.DONE)), "Phases " + complete.phases);
        check(complete.lines.stream().anyMatch(l -> l.contains("-Dhost.address=127.0.0.1")), "Local hosts option not passed");
        String saved = new String(Files.readAllBytes(analysis.toPath()), StandardCharsets.UTF_8);
        check(saved.contains("Analyzing ALL 1 workflow bases") && saved.contains("EventType=GENERATED"), "Analysis not saved: " + saved);
        check(!alive(hostPid), "Host left running after the run");
        check(complete.lines.contains("=== RUN COMPLETE ===") && complete.lines.stream().anyMatch(l -> l.startsWith("Analysis saved to ")), "Run does not end with its summary: " + complete.lines);

        // Stop while the run is still in Phase 2.
        Files.deleteIfExists(hostPid);
        Recorder stopped = new Recorder();
        Files.write(scratch.resolve("hang"), new byte[0]);   // the stand-in waits in Phase 2, as if the run never completes
        LauncherRun hanging = new LauncherRun(launcher.toFile(), scratch.toFile(), scratch.resolve("unused.txt").toFile(), false, ant.toString(), stopped);
        hanging.start();
        for (int i = 0; i < 100 && !Files.exists(hostPid); i++) Thread.sleep(100);
        check(Files.exists(hostPid), "Hanging run never started its host");
        hanging.stop();
        check(stopped.done.await(30, TimeUnit.SECONDS) && stopped.outcome == LauncherRun.Phase.STOPPED, "Stop did not stop the run: " + stopped.outcome);
        check(!alive(hostPid), "Host left running after Stop");
        check(!scratch.resolve("unused.txt").toFile().exists(), "Stopped run wrote an analysis");
        System.out.println("PASS: Run from the editor: phases followed, hosts stopped with the launcher, analysis saved and summarised, hosts read from their own logs, rule commitments per host, Stop mid-run");
    }

    /** Builds Ant's folder layout with a Launcher class that behaves like a BuildAndRun launcher. */
    private static Path fakeAnt(Path scratch) throws Exception {
        Path home = Files.createDirectories(scratch.resolve("fake-ant"));
        Path src = Files.createDirectories(home.resolve("src/org/apache/tools/ant/launch"));
        Files.write(src.resolve("Launcher.java"), (
              "package org.apache.tools.ant.launch;\n"
            + "import java.nio.file.*;\n"
            + "public class Launcher {\n"
            + "  public static void main(String[] a) throws Exception {\n"
            + "    Path scratch = Paths.get(System.getProperty(\"user.dir\")).getParent();\n"
            + "    if (String.join(\" \", a).contains(\"analyse\")) {\n"
            + "      System.out.println(\"Analyzing ALL 1 workflow bases: [1000000]\");\n"
            + "      System.out.println(\"Time=1 Token=1000000 Place=P2_Place Marking=0 Buffer=0 ToPlace=P2_Place TransitionId=EG11 EventType=GENERATED\");\n"
            + "      return;\n"
            + "    }\n"
            + "    System.out.println(\"=== PHASE 1: DATABASE INITIALIZATION ===\");\n"
            + "    System.out.println(\" INFO [main] (?:?) - === JSON-BASED DEPLOYMENT COMPLETED SUCCESSFULLY ===\");\n"
            + "    System.out.println(\"=== PHASE 2: WORKFLOW EXECUTION ===\");\n"
            + "    System.out.println(\"Deploying petrinet/Workflow/Demo (v001) for EG11...\");\n"
            + "    System.out.println(\" INFO [CommitmentListener-v001] (?:?) - [OK] UDP commitment confirmation received: v001:1\");\n"
            + "    System.out.println(\"DEBUG [main] (?:?) - Commitment confirmed for P2_Place:processToken in 101ms\");\n"
            + "    System.out.println(\"Firing 10 tokens from EG11 (v001) to P2_Place.processToken...\");\n"
            + "    Process host = new ProcessBuilder(\"sleep\", \"600\").start();\n"
            + "    Files.write(scratch.resolve(\"host.pid\"), String.valueOf(host.pid()).getBytes());\n"
            + "    if (Files.exists(scratch.resolve(\"hang\"))) { host.waitFor(); return; }\n"
            + "    System.out.println(\"=== PHASE 3: DATA COLLECTION ===\");\n"
            + "    System.out.println(\"petrinet/Workflow/Demo COMPLETED SUCCESSFULLY\");\n"
            + "    System.out.println(\"RUN FINISHED: every phase is done and Monitor holds the collected records.\");\n"
            + "    System.out.flush();\n"
            + "    host.waitFor();\n"
            + "  }\n"
            + "}\n").getBytes(StandardCharsets.UTF_8));
        Path classes = Files.createDirectories(home.resolve("classes"));
        int compiled = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.resolve("Launcher.java").toString());
        check(compiled == 0, "Stand-in Ant launcher did not compile");
        Files.createDirectories(home.resolve("lib"));
        try (java.util.jar.JarOutputStream jar = new java.util.jar.JarOutputStream(Files.newOutputStream(home.resolve("lib/ant-launcher.jar")))) {
            String entry = "org/apache/tools/ant/launch/Launcher.class";
            jar.putNextEntry(new java.util.jar.JarEntry(entry));
            jar.write(Files.readAllBytes(classes.resolve(entry)));
            jar.closeEntry();
        }
        return home;
    }

    private static boolean alive(Path pidFile) throws Exception {
        if (!Files.exists(pidFile)) return false;
        long pid = Long.parseLong(new String(Files.readAllBytes(pidFile), StandardCharsets.UTF_8).trim());
        for (int i = 0; i < 50; i++) { if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return false; Thread.sleep(100); }
        return true;
    }

    static final class Recorder implements LauncherRun.Listener {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        final Set<LauncherRun.Phase> phases = Collections.synchronizedSet(EnumSet.noneOf(LauncherRun.Phase.class));
        final CountDownLatch done = new CountDownLatch(1);
        volatile LauncherRun.Phase outcome; volatile File analysis; volatile String message;
        public void line(String text) { lines.add(text); }
        public void phase(LauncherRun.Phase phase, String detail) { phases.add(phase); }
        public void finished(LauncherRun.Phase outcome, File analysis, String message) { this.outcome = outcome; this.analysis = analysis; this.message = message; done.countDown(); }
    }

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
