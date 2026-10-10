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
        check(LauncherRun.phaseOf("=== PHASE 2: WORKFLOW EXECUTION ===") == LauncherRun.Phase.RUN && LauncherRun.phaseOf("Deploying") == null, "Phase lines");
        List<File> launchers = LauncherRun.launchersFor(repository, "petrinet/Workflow/P2_Tutorial_Workflow");
        check(!launchers.isEmpty() && launchers.get(0).getName().equals("P2_Tutorial_Workflow_BuildAndRun.xml"), "P2 launcher not found first: " + launchers);
        check(LauncherRun.analysisFileFor(repository, "petrinet/Workflow/P2_Tutorial_Workflow").getPath().replace('\\', '/')
            .endsWith("btsn.common/AnalysisFolder/PetriNet/Analysis_P2_Tutorial_Workflow.txt"), "Analysis file location");
        check(LauncherRun.antCommand("/opt/ant/bin/ant").contains("/opt/ant/bin/ant"), "Chosen Ant not used");
        if (LauncherRun.windows()) { System.out.println("PASS (Windows: process checks skipped)"); return; }

        Path scratch = Files.createTempDirectory("run-from-editor-");
        Path loader = Files.createDirectories(scratch.resolve("btsn.petrinet.ProjectLoader"));
        Path launcher = loader.resolve("Demo_BuildAndRun.xml");
        Files.write(launcher, "<project/>".getBytes(StandardCharsets.UTF_8));
        Path hostPid = scratch.resolve("host.pid");
        Path ant = scratch.resolve("fake-ant");
        Files.write(ant, ("#!/bin/sh\n"
            + "case \"$*\" in *analyse*) echo 'Analyzing ALL 1 workflow bases: [1000000]';"
            + " echo 'Time=1 Token=1000000 Place=P2_Place Marking=0 Buffer=0 ToPlace=P2_Place TransitionId=EG11 EventType=GENERATED'; exit 0;; esac\n"
            + "echo '=== PHASE 1: DATABASE INITIALIZATION ==='\n"
            + "echo ' INFO [main] (?:?) - === JSON-BASED DEPLOYMENT COMPLETED SUCCESSFULLY ==='\n"
            + "echo '=== PHASE 2: WORKFLOW EXECUTION ==='\n"
            + "sleep 600 &\n"
            + "echo $! > " + hostPid + "\n"
            + "case \"$*\" in *hang*) wait;; esac\n"
            + "echo '=== PHASE 3: DATA COLLECTION ==='\n"
            + "echo 'petrinet/Workflow/Demo COMPLETED SUCCESSFULLY'\n"
            + "echo 'Services are still running. Press Ctrl+C to stop.'\n"
            + "wait\n").getBytes(StandardCharsets.UTF_8));
        check(ant.toFile().setExecutable(true), "Fake Ant not executable");
        File analysis = scratch.resolve("btsn.common/AnalysisFolder/PetriNet/Analysis_Demo.txt").toFile();

        // A complete run: completion seen, hosts stopped, analysis saved.
        Recorder complete = new Recorder();
        LauncherRun run = new LauncherRun(launcher.toFile(), scratch.toFile(), analysis, true, ant.toString(), complete);
        run.settleMillis = 300; run.pollMillis = 100;
        run.start();
        check(complete.done.await(60, TimeUnit.SECONDS), "Run did not finish");
        check(complete.outcome == LauncherRun.Phase.DONE && analysis.equals(complete.analysis), "Run outcome " + complete.outcome + ": " + complete.message + "\n" + complete.lines);
        check(complete.phases.containsAll(Arrays.asList(LauncherRun.Phase.BUILD, LauncherRun.Phase.INITIALISE, LauncherRun.Phase.RUN,
            LauncherRun.Phase.COLLECT, LauncherRun.Phase.SETTLE, LauncherRun.Phase.ANALYSE, LauncherRun.Phase.DONE)), "Phases " + complete.phases);
        check(complete.lines.stream().anyMatch(l -> l.contains("-Dhost.address=127.0.0.1")), "Local hosts option not passed");
        String saved = new String(Files.readAllBytes(analysis.toPath()), StandardCharsets.UTF_8);
        check(saved.contains("Analyzing ALL 1 workflow bases") && saved.contains("EventType=GENERATED"), "Analysis not saved: " + saved);
        check(!alive(hostPid), "Host left running after the run");

        // Stop while the run is still in Phase 2.
        Files.deleteIfExists(hostPid);
        Recorder stopped = new Recorder();
        LauncherRun hanging = new LauncherRun(launcher.toFile(), scratch.toFile(), scratch.resolve("unused.txt").toFile(), false, hangingAnt(scratch, ant), stopped);
        hanging.start();
        for (int i = 0; i < 100 && !Files.exists(hostPid); i++) Thread.sleep(100);
        check(Files.exists(hostPid), "Hanging run never started its host");
        hanging.stop();
        check(stopped.done.await(30, TimeUnit.SECONDS) && stopped.outcome == LauncherRun.Phase.STOPPED, "Stop did not stop the run: " + stopped.outcome);
        check(!alive(hostPid), "Host left running after Stop");
        check(!scratch.resolve("unused.txt").toFile().exists(), "Stopped run wrote an analysis");
        System.out.println("PASS: Run from the editor: phases followed, hosts stopped with the launcher, analysis saved, Stop mid-run");
    }

    /** The fake Ant again, told to wait in Phase 2 as if the run never completes. */
    private static String hangingAnt(Path scratch, Path ant) throws Exception {
        Path wrapper = scratch.resolve("fake-ant-hang");
        Files.write(wrapper, ("#!/bin/sh\nexec " + ant + " \"$@\" hang\n").getBytes(StandardCharsets.UTF_8));
        wrapper.toFile().setExecutable(true);
        return wrapper.toString();
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
