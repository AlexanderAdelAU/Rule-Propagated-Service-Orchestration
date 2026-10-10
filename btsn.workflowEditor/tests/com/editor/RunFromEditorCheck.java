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
        // Ant is found from its folder or anything inside it; a launcher in the Ant box is refused.
        File antHome = LauncherRun.antHome("");
        check(new File(antHome, "lib/ant-launcher.jar").isFile(), "Ant not found automatically: " + antHome);
        check(antHome.equals(LauncherRun.antHome(new File(antHome, "bin").getPath())) && antHome.equals(LauncherRun.antHome(new File(antHome, "lib/ant-launcher.jar").getPath())), "Ant not found from inside its folder");
        try { LauncherRun.antHome(new File(repository, "btsn.petrinet.ProjectLoader/P2_Tutorial_Workflow_BuildAndRun.xml").getPath()); check(false, "Launcher accepted as Ant"); }
        catch (java.io.IOException expected) { check(expected.getMessage().contains("holds a launcher"), "Wrong message: " + expected.getMessage()); }
        check(LauncherRun.antLaunch(antHome).contains("org.apache.tools.ant.launch.Launcher"), "Ant not run through its launcher class");
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
        Files.write(scratch.resolve("hang"), new byte[0]);   // the stand-in waits in Phase 2, as if the run never completes
        LauncherRun hanging = new LauncherRun(launcher.toFile(), scratch.toFile(), scratch.resolve("unused.txt").toFile(), false, ant.toString(), stopped);
        hanging.start();
        for (int i = 0; i < 100 && !Files.exists(hostPid); i++) Thread.sleep(100);
        check(Files.exists(hostPid), "Hanging run never started its host");
        hanging.stop();
        check(stopped.done.await(30, TimeUnit.SECONDS) && stopped.outcome == LauncherRun.Phase.STOPPED, "Stop did not stop the run: " + stopped.outcome);
        check(!alive(hostPid), "Host left running after Stop");
        check(!scratch.resolve("unused.txt").toFile().exists(), "Stopped run wrote an analysis");
        System.out.println("PASS: Run from the editor: phases followed, hosts stopped with the launcher, analysis saved, Stop mid-run");
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
            + "    Process host = new ProcessBuilder(\"sleep\", \"600\").start();\n"
            + "    Files.write(scratch.resolve(\"host.pid\"), String.valueOf(host.pid()).getBytes());\n"
            + "    if (Files.exists(scratch.resolve(\"hang\"))) { host.waitFor(); return; }\n"
            + "    System.out.println(\"=== PHASE 3: DATA COLLECTION ===\");\n"
            + "    System.out.println(\"petrinet/Workflow/Demo COMPLETED SUCCESSFULLY\");\n"
            + "    System.out.println(\"Services are still running. Press Ctrl+C to stop.\");\n"
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
