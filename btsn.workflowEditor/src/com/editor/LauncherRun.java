package com.editor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runs a BuildAndRun launcher from the editor, start to replay:
 * <ol>
 *   <li>runs the launcher's default target with Ant and reports its output and phases;</li>
 *   <li>after the launcher's last line ("Services are still running") waits until Monitor's database stops changing, then stops
 *       the launcher and every host it started (the whole process tree);</li>
 *   <li>runs the launcher's analyse target and saves its output as the run's analysis file.</li>
 * </ol>
 * The hosts are child processes of Ant, so stopping only Ant would leave them running (on Windows
 * especially), holding their ports and Monitor's database. Stopping always takes the whole tree.
 */
public final class LauncherRun {
    public enum Phase { BUILD, INITIALISE, RUN, COLLECT, SETTLE, ANALYSE, DONE, FAILED, STOPPED }

    public interface Listener {
        void line(String text);
        void phase(Phase phase, String detail);
        /** Called once; analysis is the saved file when the run was analysed, otherwise null. */
        void finished(Phase outcome, File analysis, String message);
    }

    private final File launcher, root, analysisFile;
    private final boolean allHostsHere;
    private final String antCommand;
    private final Listener listener;
    /** How long Monitor's database must stay unchanged before the hosts are stopped, and the longest wait. */
    long settleMillis = 6000, settleLimitMillis = 120000, pollMillis = 2000;
    private volatile Process process;
    private volatile boolean stopRequested;
    private Thread worker, shutdownHook;

    public LauncherRun(File launcher, File root, File analysisFile, boolean allHostsHere, String antCommand, Listener listener) {
        this.launcher = launcher; this.root = root; this.analysisFile = analysisFile;
        this.allHostsHere = allHostsHere; this.antCommand = antCommand; this.listener = listener;
    }

    public boolean isRunning() { return worker != null && worker.isAlive(); }

    public void start() {
        if (isRunning()) throw new IllegalStateException("Already running");
        shutdownHook = new Thread(() -> { Process p = process; if (p != null) stopTree(p); });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        worker = new Thread(this::runAll, "launcher-run");
        worker.setDaemon(true);
        worker.start();
    }

    /** Stop now: the launcher, its hosts and any analysis in progress. */
    public void stop() {
        stopRequested = true;
        Process p = process;
        if (p != null) stopTree(p);
    }

    private void runAll() {
        Phase outcome = Phase.FAILED; File analysis = null; String message;
        try {
            listener.phase(Phase.BUILD, "Building and starting the hosts");
            boolean completed = runLauncher();
            if (stopRequested) { outcome = Phase.STOPPED; message = "Stopped."; }
            else if (!completed) message = "The run did not complete; see the output above.";
            else {
                listener.phase(Phase.SETTLE, "Waiting for Monitor to finish writing");
                waitForMonitor();
                stopTree(process);
                listener.line("[editor] Hosts stopped.");
                if (stopRequested) { outcome = Phase.STOPPED; message = "Stopped."; }
                else {
                    listener.phase(Phase.ANALYSE, "Analysing");
                    if (analyse()) { outcome = Phase.DONE; analysis = analysisFile; message = "Analysis saved to " + analysisFile.getName() + "."; }
                    else message = stopRequested ? "Stopped." : "The analysis failed; see the output above.";
                    if (stopRequested) outcome = Phase.STOPPED;
                }
            }
        } catch (IOException ex) {
            message = ex.getMessage();
        } catch (InterruptedException ex) {
            outcome = Phase.STOPPED; message = "Stopped.";
        } finally {
            Process p = process; if (p != null) stopTree(p);
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) { }
        }
        listener.phase(outcome, "");
        listener.finished(outcome, analysis, message);
    }

    /** Runs the default target; true once it reports its last line (the hosts are then still running). */
    private boolean runLauncher() throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(Arrays.asList("-emacs", "-f", launcher.getName()));
        if (allHostsHere) args.add("-Dhost.address=127.0.0.1");
        process = start(args, null);
        boolean completed = false;
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = out.readLine()) != null; ) {
                listener.line(line);
                Phase phase = phaseOf(line);
                if (phase != null) listener.phase(phase, line.trim());
                // The launcher's last line; earlier steps also print "... COMPLETED SUCCESSFULLY".
                if (line.contains("Services are still running")) { completed = true; break; }
                if (line.startsWith("BUILD FAILED")) break;
            }
        }
        if (!completed) process.waitFor(10, TimeUnit.SECONDS);
        return completed && !stopRequested;
    }

    /** Runs the analyse target, saving its output (the analyzer report) as the analysis file. */
    private boolean analyse() throws IOException, InterruptedException {
        Files.createDirectories(analysisFile.getParentFile().toPath());
        process = start(Arrays.asList("-emacs", "-f", launcher.getName(), "analyse"), null);
        StringBuilder report = new StringBuilder();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = out.readLine()) != null; ) { listener.line(line); report.append(line).append('\n'); }
        }
        int exit = process.waitFor();
        process = null;
        if (exit != 0 || stopRequested) return false;
        Files.write(analysisFile.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        return true;
    }

    /** Monitor writes collected records after the last collector returns; wait until its database is quiet. */
    private void waitForMonitor() throws InterruptedException {
        File database = new File(root, "btsn.common.Monitor/ServiceAnalysisDataBase");
        long start = System.currentTimeMillis(), last = latestChange(database), quietSince = System.currentTimeMillis();
        while (!stopRequested && System.currentTimeMillis() - start < settleLimitMillis) {
            Thread.sleep(pollMillis);
            long now = latestChange(database);
            if (now != last) { last = now; quietSince = System.currentTimeMillis(); }
            else if (System.currentTimeMillis() - quietSince >= settleMillis) return;
        }
    }

    static long latestChange(File dir) {
        if (!dir.exists()) return 0;
        try (java.util.stream.Stream<Path> files = Files.walk(dir.toPath())) {
            return files.mapToLong(p -> p.toFile().lastModified()).max().orElse(0);
        } catch (IOException | UncheckedIOException ex) { return 0; }
    }

    private Process start(List<String> args, File output) throws IOException {
        File home = antHome(antCommand);
        List<String> command = antLaunch(home);
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).directory(launcher.getParentFile()).redirectErrorStream(true);
        builder.environment().put("ANT_HOME", home.getPath());
        listener.line("[editor] Ant " + home.getPath() + " on Java " + command.get(0));
        listener.line("[editor] ant " + String.join(" ", args));
        return builder.start();
    }

    /**
     * Ant runs through its own launcher class on the editor's Java (a JDK, which Ant needs to compile),
     * exactly as Ant's scripts do, but without a shell: no cmd.exe quoting, no JAVA_HOME to set.
     */
    static List<String> antLaunch(File home) {
        File java = javaProgram(new File(System.getProperty("java.home")));
        String env = System.getenv("JAVA_HOME");
        if (!hasCompiler(new File(System.getProperty("java.home"))) && env != null && hasCompiler(new File(env))) java = javaProgram(new File(env));
        return new ArrayList<>(Arrays.asList(java.getPath(), "-Dant.home=" + home.getPath(),
            "-cp", new File(home, "lib/ant-launcher.jar").getPath(), "org.apache.tools.ant.launch.Launcher"));
    }
    private static File javaProgram(File javaHome) { return new File(javaHome, "bin/" + (windows() ? "java.exe" : "java")); }
    private static boolean hasCompiler(File javaHome) { return new File(javaHome, "bin/" + (windows() ? "javac.exe" : "javac")).isFile(); }

    /**
     * Ant's folder (the one holding lib/ant-launcher.jar): the chosen folder or a file inside it, else
     * ANT_HOME, else the 'ant' on the PATH, else the Ant inside the Eclipse that started the editor.
     */
    static File antHome(String configured) throws IOException {
        String chosen = configured == null ? "" : configured.trim();
        if (!chosen.isEmpty()) {
            if (chosen.toLowerCase(Locale.ROOT).endsWith(".xml"))
                throw new IOException("The Ant box holds a launcher (" + new File(chosen).getName() + "). Choose Ant's folder instead, or leave the box empty to find Ant automatically.");
            File home = homeAround(new File(chosen));
            if (home == null) throw new IOException("No Ant found at " + chosen + ". Choose Ant's folder (it contains lib/ant-launcher.jar), or leave the box empty.");
            return home;
        }
        List<File> candidates = new ArrayList<>();
        String env = System.getenv("ANT_HOME");
        if (env != null && !env.isEmpty()) candidates.add(new File(env));
        String path = System.getenv("PATH");
        if (path != null) for (String dir : path.split(File.pathSeparator))
            for (String name : new String[] {"ant", "ant.bat", "ant.cmd"}) { File f = new File(dir, name); if (f.isFile()) candidates.add(f); }
        for (File home : candidates) { File found = homeAround(home); if (found != null) return found; }
        File eclipse = eclipseAnt();
        if (eclipse != null) return eclipse;
        throw new IOException("Ant was not found (no ANT_HOME, no 'ant' on the PATH, no Ant in Eclipse's plugins). "
            + "Choose Ant's folder in this window: an Apache Ant install, or Eclipse's plugins/org.apache.ant_* folder.");
    }

    /** The Ant home at or above a file or folder: the first folder with lib/ant-launcher.jar (symbolic links followed). */
    static File homeAround(File start) {
        // As given first (a packaged Ant links lib/ant-launcher.jar to a shared jar), then with links resolved
        // (a PATH entry such as /usr/bin/ant links into Ant's folder).
        List<File> starts = new ArrayList<>(Collections.singletonList(start.getAbsoluteFile()));
        try { starts.add(start.toPath().toRealPath().toFile()); } catch (IOException | InvalidPathException ignored) { }
        for (File first : starts) {
            File f = first;
            for (int up = 0; f != null && up < 4; up++, f = f.getParentFile())
                if (new File(f, "lib/ant-launcher.jar").isFile()) return f;
        }
        return null;
    }

    /** Eclipse ships Ant as plugins/org.apache.ant_<version>; look beside the Java and programs that started the editor. */
    static File eclipseAnt() {
        List<File> places = new ArrayList<>();
        places.add(new File(System.getProperty("java.home")));
        for (Optional<ProcessHandle> p = ProcessHandle.current().parent(); p.isPresent(); p = p.get().parent())
            p.get().info().command().ifPresent(c -> places.add(new File(c)));
        return eclipseAnt(places);
    }

    /** The newest plugins/org.apache.ant_* found at or above any of these files. */
    static File eclipseAnt(List<File> places) {
        for (File place : places) {
            for (File dir = place.getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
                File plugins = "plugins".equals(dir.getName()) ? dir : new File(dir, "plugins");
                File[] ants = plugins.listFiles(f -> f.isDirectory() && f.getName().startsWith("org.apache.ant_") && new File(f, "lib/ant-launcher.jar").isFile());
                if (ants != null && ants.length > 0) { Arrays.sort(ants); return ants[ants.length - 1]; }
            }
        }
        return null;
    }

    static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"); }

    /** Stops a process and everything it started, politely first, then forcibly. */
    static void stopTree(Process process) {
        List<ProcessHandle> tree = process.descendants().collect(Collectors.toList());
        tree.add(process.toHandle());
        for (ProcessHandle h : tree) h.destroy();
        long deadline = System.currentTimeMillis() + 10000;
        for (ProcessHandle h : tree) {
            try { h.onExit().get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS); }
            catch (Exception timedOut) { h.destroyForcibly(); }
        }
    }

    /** The phase a launcher output line starts, if any. */
    static Phase phaseOf(String line) {
        if (line.contains("PHASE 1:")) return Phase.INITIALISE;
        if (line.contains("PHASE 2:")) return Phase.RUN;
        if (line.contains("PHASE 3:")) return Phase.COLLECT;
        return null;
    }

    /** BuildAndRun launchers in the project-loader folders that run this process; Build and Run's own name first. */
    public static List<File> launchersFor(File root, String processName) {
        List<File> found = new ArrayList<>();
        File[] loaders = root.listFiles(f -> f.isDirectory() && f.getName().matches("btsn\\..+\\.ProjectLoader"));
        if (loaders == null) return found;
        String property = "name=\"workflow.process.name\" value=\"" + processName + "\"";
        for (File loader : loaders) {
            File[] xmls = loader.listFiles(f -> f.getName().endsWith(".xml"));
            if (xmls == null) continue;
            for (File xml : xmls) {
                try { if (new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8).contains(property)) found.add(xml); }
                catch (IOException ignored) { }
            }
        }
        String base = processName.substring(processName.lastIndexOf('/') + 1) + "_BuildAndRun.xml";
        found.sort(Comparator.comparing((File f) -> !f.getName().equals(base)).thenComparing(File::getName));
        return found;
    }

    /** btsn.common/AnalysisFolder/&lt;Domain&gt;/Analysis_&lt;process&gt;.txt, beside the analyses saved by hand. */
    public static File analysisFileFor(File root, String processName) {
        String domain = processName.contains("/") ? processName.substring(0, processName.indexOf('/')) : "";
        String folder = "petrinet".equals(domain) || domain.isEmpty() ? "PetriNet"
            : Character.toUpperCase(domain.charAt(0)) + domain.substring(1);
        String base = processName.substring(processName.lastIndexOf('/') + 1);
        return new File(root, "btsn.common/AnalysisFolder/" + folder + "/Analysis_" + base + ".txt");
    }
}
