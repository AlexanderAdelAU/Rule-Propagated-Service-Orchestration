package org.btsn.deployment;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * After the last token, a run collects once the workflows have finished: nothing written by this machine's hosts or
 * Monitor for the quiet time. A process that keeps moving (a closed net) is collected at the limit instead.
 */
public final class HostDrainCheck {
    public static void main(String[] args) throws Exception {
        Path run = Files.createTempDirectory("drain");
        Path database = Files.createDirectories(run.resolve("P1/ServiceAnalysisDataBase"));
        Path monitor = Files.createDirectories(run.resolve("Monitor"));
        Path plan = run.resolve("hosts.properties");
        Files.writeString(plan, "hosts.local=P1\nhosts.remote=P2\n");
        Map<String, String> a = Map.of("plan", plan.toString(), "runtime", run.toString(), "monitor", monitor.toString(), "quiet", "1", "limit", "20");

        // Workflows that move for about 2 s, then stop: collected about 1 s later, long before the limit.
        Thread busy = writer(database.resolve("log.dat"), 2000);
        long start = System.currentTimeMillis();
        String out = capture(a);
        long took = System.currentTimeMillis() - start;
        busy.join();
        check(out.contains("WORKFLOWS FINISHED") && took >= 2500 && took < 8000, "Finished workflows not seen in time (" + took + " ms): " + out);
        check(out.contains("P2 on other machines: not watched"), "Remote host not named: " + out);

        // Monitor's writes count as activity too (a hand-over to Monitor ends a workflow).
        busy = writer(monitor.resolve("log.dat"), 2000);
        start = System.currentTimeMillis();
        out = capture(a);
        took = System.currentTimeMillis() - start;
        busy.join();
        check(out.contains("WORKFLOWS FINISHED") && took >= 2500, "Monitor activity ignored (" + took + " ms): " + out);

        // A closed net never stops moving: collected at the limit.
        busy = writer(database.resolve("log.dat"), 5000);
        out = capture(Map.of("plan", plan.toString(), "runtime", run.toString(), "quiet", "1", "limit", "3"));
        busy.join();
        check(out.contains("WORKFLOWS STILL RUNNING at the 3 s limit"), "Limit not applied: " + out);
        System.out.println("PASS: the run collects once nothing has moved on the hosts or Monitor for the quiet time, names unwatched remote hosts, and stops at the limit for a net that keeps moving");
    }

    private static Thread writer(Path file, long millis) {
        Thread t = new Thread(() -> {
            long end = System.currentTimeMillis() + millis;
            try {
                while (System.currentTimeMillis() < end) {
                    Files.writeString(file, Long.toString(System.nanoTime()));
                    file.toFile().setLastModified(System.currentTimeMillis());
                    Thread.sleep(200);
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        t.start();
        return t;
    }

    private static String capture(Map<String, String> a) throws Exception {
        PrintStream previous = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bytes, true));
        try { HostLaunch.drain(a); } finally { System.setOut(previous); }
        return bytes.toString();
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
