package org.btsn.derby.Analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts timestamps recorded on other machines onto the clock of the ip0 machine (the launcher, event generators and
 * Monitor), for replays and charts. The database keeps every host's own timestamps; this only shifts what is shown.
 *
 * Places on one channel run on one host and share its clock. A token sent from one host to another cannot arrive
 * before it was sent, so each hop bounds the offset between their clocks (see PetriNetAnalyzer.ClockOffsetBound).
 * Combining the hops between all channels gives, for each channel, the range its offset from ip0 must lie in. With
 * hops both ways the estimate is the middle of that range (the asymmetry of the two fastest trips is the only error);
 * with hops one way only it is the smallest shift that keeps every hop forward in time. A single-host run, or one whose
 * infrastructure is not known, is left as recorded.
 */
public final class ClockAlignment {
    public static final String REFERENCE = "ip0";
    private static final Pattern ACTIVE_SERVICE = Pattern.compile(
        "<Rel>\\s*activeService\\s*</Rel>\\s*<Ind>\\s*([^<]+?)\\s*</Ind>\\s*<Ind>[^<]*</Ind>\\s*<Ind>\\s*([^<]+?)\\s*</Ind>");

    /** One channel's clock relative to ip0: clock(channel) - clock(ip0), estimated, and its bounds (null when open). */
    public static final class Channel {
        public final String channel; public final List<String> places = new ArrayList<>();
        public Long lower, upper; public long offset;
        Channel(String channel) { this.channel = channel; }
        public String describe() {
            String range = lower != null && upper != null ? "bounds " + lower + ".." + upper + " ms"
                : upper != null ? "at most " + upper + " ms, one-way hops only"
                : lower != null ? "at least " + lower + " ms, one-way hops only" : "no hops to ip0";
            return channel + " " + places + ": " + (offset > 0 ? "+" : "") + offset + " ms (" + range + ")";
        }
    }

    private final Map<String, String> channelOfPlace;
    private final Map<String, Channel> channels = new TreeMap<>();
    private String problem;

    private ClockAlignment(Map<String, String> channelOfPlace) { this.channelOfPlace = channelOfPlace; }

    /** The timestamp a place recorded, on the ip0 clock. */
    public long toReference(String place, long timestamp) {
        String channel = place == null ? null : channelOfPlace.get(place);
        Channel c = channel == null ? null : channels.get(channel);
        return c == null ? timestamp : timestamp - c.offset;
    }
    /** ms added to a place's timestamps. */
    public long correction(String place) { return toReference(place, 0); }
    public boolean shiftsAnything() { return channels.values().stream().anyMatch(c -> c.offset != 0); }
    /** The channels other than ip0, with their estimates; empty for a single host. */
    public List<Channel> channels() { return new ArrayList<>(channels.values()); }
    /** Why the timestamps were left as recorded, or null. */
    public String problem() { return problem; }

    /** Everyone's clock is ip0's: nothing moves. */
    public static ClockAlignment none() { return new ClockAlignment(new TreeMap<>()); }

    public static ClockAlignment estimate(Map<String, PetriNetAnalyzer.ClockOffsetBound> bounds, Map<String, String> channelOfPlace) {
        ClockAlignment a = new ClockAlignment(channelOfPlace);
        TreeSet<String> names = new TreeSet<>(channelOfPlace.values());
        names.add(REFERENCE);
        List<String> list = new ArrayList<>(names);
        int n = list.size(), ref = list.indexOf(REFERENCE);
        // w[i][j]: the tightest known upper bound on clock(j) - clock(i).
        long[][] w = new long[n][n];
        boolean[][] known = new boolean[n][n];
        for (int i = 0; i < n; i++) known[i][i] = true;
        for (PetriNetAnalyzer.ClockOffsetBound b : bounds.values()) {
            String ca = channelOfPlace.get(b.placeA), cb = channelOfPlace.get(b.placeB);
            if (ca == null || cb == null || ca.equals(cb)) continue;
            int i = list.indexOf(ca), j = list.indexOf(cb);
            if (b.upper != null) tighten(w, known, i, j, b.upper);
            if (b.lower != null) tighten(w, known, j, i, -b.lower);
        }
        for (int k = 0; k < n; k++)
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    if (known[i][k] && known[k][j]) tighten(w, known, i, j, w[i][k] + w[k][j]);
        for (int i = 0; i < n; i++)
            if (w[i][i] < 0) {
                a.problem = "the hops to and from " + list.get(i) + " fit no single clock offset (did a clock change during the run?)";
                return a;
            }
        for (int i = 0; i < n; i++) {
            if (i == ref) continue;
            Channel c = new Channel(list.get(i));
            for (Map.Entry<String, String> e : channelOfPlace.entrySet()) if (e.getValue().equals(c.channel)) c.places.add(e.getKey());
            if (known[ref][i]) c.upper = w[ref][i];
            if (known[i][ref]) c.lower = -w[i][ref];
            if (c.lower != null && c.upper != null) c.offset = Math.floorDiv(c.lower + c.upper, 2);
            else if (c.upper != null) c.offset = Math.min(0, c.upper);
            else if (c.lower != null) c.offset = Math.max(0, c.lower);
            a.channels.put(c.channel, c);
        }
        return a;
    }

    private static void tighten(long[][] w, boolean[][] known, int i, int j, long value) {
        if (!known[i][j] || value < w[i][j]) { w[i][j] = value; known[i][j] = true; }
    }

    /** Place (or other runtime) -> channel, from a launcher's generated deployment rules. Empty if the file is absent. */
    public static Map<String, String> placeChannels(Path generatedRules) {
        Map<String, String> channels = new TreeMap<>();
        try {
            if (generatedRules == null || !Files.isRegularFile(generatedRules)) return channels;
            Matcher m = ACTIVE_SERVICE.matcher(Files.readString(generatedRules));
            while (m.find()) channels.putIfAbsent(m.group(1), m.group(2));
        } catch (Exception e) {
            System.err.println("Clock alignment: cannot read " + generatedRules + ": " + e.getMessage());
        }
        return channels;
    }

    /** The generated deployment rules of the run being analysed: btsn.common.dir is set by the launcher's analysis targets. */
    public static Map<String, String> placeChannels() {
        String common = System.getProperty("btsn.common.dir");
        return common == null || common.isBlank() ? new TreeMap<>()
            : placeChannels(Path.of(common, "RuleBase", "Generated", "InfrastructureDeployment.ruleml.xml"));
    }

    /** Alignment from the recorded hops (EXIT toward a place, BUFFERED arrival there); workflowBase null means every workflow. */
    public static ClockAlignment fromDatabase(Connection conn, Integer workflowBase, Map<String, String> channelOfPlace) throws SQLException {
        if (new TreeSet<>(channelOfPlace.values()).size() < 2) return estimate(new TreeMap<>(), channelOfPlace);
        List<PetriNetAnalyzer.Hop> sends = new ArrayList<>(), arrivals = new ArrayList<>();
        String sql = "SELECT tokenId, timestamp, placeName, toPlace, eventType FROM CONSOLIDATED_TRANSITION_FIRINGS "
            + "WHERE eventType IN ('EXIT', 'BUFFERED')" + (workflowBase == null ? "" : " AND workflowBase = ?");
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            if (workflowBase != null) pstmt.setInt(1, workflowBase);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String to = rs.getString("toPlace");
                if (to == null || !to.endsWith("_Place")) continue;
                PetriNetAnalyzer.Hop hop = "EXIT".equals(rs.getString("eventType"))
                    ? new PetriNetAnalyzer.Hop(rs.getInt("tokenId"), rs.getString("placeName"), to, rs.getLong("timestamp"))
                    : new PetriNetAnalyzer.Hop(rs.getInt("tokenId"), null, to, rs.getLong("timestamp"));
                (hop.from == null ? arrivals : sends).add(hop);
            }
        }
        return estimate(PetriNetAnalyzer.clockOffsetBounds(sends, arrivals), channelOfPlace);
    }
}
