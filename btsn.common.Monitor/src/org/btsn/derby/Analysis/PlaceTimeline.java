package org.btsn.derby.Analysis;

import java.sql.*;
import java.util.*;

/**
 * Prints, for each visit to the given places, when the token arrived, how long it waited
 * before ENTER, and how long the place was busy with it (ENTER to its last EXIT/TERMINATE
 * there). In Eclipse: Run As > Java Application from btsn.common.Monitor, whose working
 * directory holds ServiceAnalysisDataBase. Program arguments are the places (default P1_Place
 * P2_Place); -Ddb=<path> reads another database.
 */
public class PlaceTimeline {
    public static void main(String[] args) throws Exception {
        String db = System.getProperty("db", "ServiceAnalysisDataBase");
        Set<String> places = new LinkedHashSet<>(Arrays.asList(args.length > 0 ? args : new String[] {"P1_Place", "P2_Place"}));
        List<String[]> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:derby:" + db);
             ResultSet r = c.createStatement().executeQuery(
                 "SELECT tokenId, eventType, timestamp, placeName FROM CONSOLIDATED_TRANSITION_FIRINGS ORDER BY timestamp, id")) {
            while (r.next()) rows.add(new String[] {r.getString(1), r.getString(2), r.getString(3), r.getString(4)});
        }
        long t0 = Long.parseLong(rows.get(0)[2]);
        Map<Long, Long> lastLeave = new HashMap<>();          // workflow key -> time it last left a place
        Map<String, long[]> visits = new LinkedHashMap<>();    // key|place -> {arrive, enter, leave}
        for (String[] e : rows) {
            long tok = Long.parseLong(e[0]), wf = tok - tok % 10000, ts = Long.parseLong(e[2]);
            String key = wf + "|" + e[3];
            switch (e[1]) {
                case "GENERATED": lastLeave.put(wf, ts); break;
                case "ENTER":
                    if (!visits.containsKey(key)) visits.put(key, new long[] {lastLeave.getOrDefault(wf, ts), ts, -1});
                    break;
                case "EXIT": case "TERMINATE":
                    long[] v = visits.get(key);
                    if (v != null) v[2] = Math.max(v[2], ts);
                    lastLeave.put(wf, ts);
                    break;
                default:
            }
        }
        for (String place : places) {
            System.out.println("== " + place + "\n arrive_s  version  wait_ms  busy_ms");
            visits.entrySet().stream().filter(x -> x.getKey().endsWith("|" + place))
                .sorted(Comparator.comparingLong(x -> x.getValue()[0]))
                .forEach(x -> {
                    long wf = Long.parseLong(x.getKey().split("\\|")[0]); long[] v = x.getValue();
                    System.out.printf("%8.1f   v%03d  %7d  %7s%n", (v[0] - t0) / 1000.0, wf / 1_000_000,
                        v[1] - v[0], v[2] < 0 ? "-" : String.valueOf(v[2] - v[1]));
                });
        }
    }
}