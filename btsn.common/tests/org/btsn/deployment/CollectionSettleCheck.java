package org.btsn.deployment;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collection is finished only when Monitor has stored every host's records, for every version collected: a quiet
 * Monitor database is not enough, because a slow collector may not have sent anything yet.
 */
public final class CollectionSettleCheck {
    public static void main(String[] args) throws Exception {
        Path run = Files.createTempDirectory("settle");
        Path database = Files.createDirectories(run.resolve("ServiceAnalysisDataBase"));
        Path receipts = run.resolve(CollectionReceipts.FOLDER);
        Path plan = run.resolve("hosts.properties");
        Files.writeString(plan, "hosts.list=P1,P2,P3\n");
        Map<String, String> a = new HashMap<>(Map.of("database", database.toString(), "receipts", receipts.toString(),
            "plan", plan.toString(), "versions", "v001,v002", "quiet", "1", "limit", "8"));

        // Receipts left by an earlier run do not count: collect clears them before it sends its requests.
        for (String place : List.of("P1_Place", "P2_Place", "P3_Place"))
            for (String version : List.of("v001", "v002")) CollectionReceipts.stored(receipts, place, version, "earlier run");
        CollectionReceipts.clear(receipts);
        check(CollectionReceipts.missing(receipts, List.of("P1", "P2", "P3"), List.of("v001", "v002")).size() == 3,
              "cleared receipts must all be missing");

        // Monitor's database is quiet, but P3 sends its records only after 3 s: the collection waits for them.
        storedNow(receipts, "P1_Place", "P2_Place");
        Thread late = later(3000, receipts, "P3_Place");
        long start = System.currentTimeMillis();
        String out = capture(a);
        long took = System.currentTimeMillis() - start;
        late.join();
        check(took >= 3000 && out.contains("Monitor has stored P3's records")
              && out.contains("Monitor has stored every host's records (P1, P2, P3; v001, v002)") && out.contains("Monitor has stored the collected records"),
              "a slow host's records not waited for (" + took + " ms): " + out);

        // Still missing at the limit: the run stops and names the host and version.
        CollectionReceipts.clear(receipts);
        storedNow(receipts, "P1_Place", "P2_Place");
        CollectionReceipts.stored(receipts, "P3_Place", "v001", "only v001");
        a.put("limit", "2");
        String failure = failure(a);
        check(failure.startsWith("COLLECTION INCOMPLETE after 2 s") && failure.contains("P3 (v002)") && !failure.contains("P1"),
              "missing host not named: " + failure);

        // A collector that reported an error counts as answered, and the run stops with its reason.
        CollectionReceipts.failed(receipts, "P3_Place", "database locked");
        failure = failure(a);
        check(failure.contains("collectors failed - P3: database locked") && !failure.contains("after 2 s"),
              "collector failure not reported: " + failure);

        // Without receipts (Monitor on another machine, or an older call): only the quiet database is waited for.
        out = capture(Map.of("database", database.toString(), "quiet", "1", "limit", "5"));
        check(out.contains("Monitor has stored the collected records"), "quiet-only settle broken: " + out);
        System.out.println("PASS: collection waits for every host's records for every version, ignores an earlier run's receipts, and names any host still missing at the limit or whose collector failed");
    }

    private static void storedNow(Path receipts, String... places) throws Exception {
        for (String place : places)
            for (String version : List.of("v001", "v002")) CollectionReceipts.stored(receipts, place, version, "stored");
    }

    private static Thread later(long millis, Path receipts, String place) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(millis); storedNow(receipts, place); } catch (Exception e) { throw new RuntimeException(e); }
        });
        t.start();
        return t;
    }

    private static String failure(Map<String, String> a) throws Exception {
        try {
            capture(a);
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        throw new AssertionError("settle should have failed: " + a);
    }

    private static String capture(Map<String, String> a) throws Exception {
        PrintStream previous = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bytes, true));
        try { HostLaunch.settle(a); } finally { System.setOut(previous); }
        return bytes.toString();
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
