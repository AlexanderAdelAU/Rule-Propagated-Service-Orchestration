import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.btsn.derby.Analysis.ClockAlignment;
import org.btsn.derby.Analysis.PetriNetAnalyzer;
import org.btsn.derby.Analysis.PetriNetAnalyzer.ClockOffsetBound;
import org.btsn.derby.Analysis.PetriNetAnalyzer.Hop;

/**
 * Aligning other machines' timestamps to the ip0 clock, using the three-machine fork/join run's records: P1 and P4 on
 * 192.168.1.82 (ip0), P2 on .152 (ip1, about 22 s slow), P3 on .90 (ip2, about 3 s slow). Without alignment the replay
 * showed P2's tokens 22 s before P1 had sent them.
 */
public final class ClockAlignmentCheck {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        // {token, P1 EXIT, branch arrival (BUFFERED), branch EXIT, P4 arrival (BUFFERED)}
        long[][] p2 = {
            {1010001, 1791806635594L, 1791806613328L, 1791806613417L, 1791806636143L},
            {1020001, 1791806636577L, 1791806614291L, 1791806614383L, 1791806637064L},
            {1030001, 1791806637573L, 1791806615279L, 1791806615363L, 1791806638052L},
            {1040001, 1791806638562L, 1791806616274L, 1791806616351L, 1791806639031L},
            {1050001, 1791806639553L, 1791806617275L, 1791806617363L, 1791806640043L},
            {1080001, 1791806642516L, 1791806620324L, 1791806620410L, 1791806643098L}};
        long[][] p3 = {
            {1010002, 1791806635690L, 1791806632780L, 1791806633027L, 1791806636540L},
            {1020002, 1791806636680L, 1791806633744L, 1791806633887L, 1791806637320L},
            {1030002, 1791806637676L, 1791806634734L, 1791806634894L, 1791806638349L},
            {1040002, 1791806638680L, 1791806635709L, 1791806635828L, 1791806639251L},
            {1050002, 1791806639664L, 1791806636696L, 1791806636800L, 1791806640214L},
            {1080002, 1791806642655L, 1791806639670L, 1791806639750L, 1791806643223L}};
        List<Hop> sends = new ArrayList<>(), arrivals = new ArrayList<>();
        hops(p2, "P2_Place", sends, arrivals);
        hops(p3, "P3_Place", sends, arrivals);
        Map<String, ClockOffsetBound> bounds = PetriNetAnalyzer.clockOffsetBounds(sends, arrivals);
        check(bounds.get("P1_Place / P2_Place").upper == -22294 && bounds.get("P2_Place / P4_Place").upper == 22680, "Run bounds changed");

        Map<String, String> channels = placeChannels();
        ClockAlignment a = ClockAlignment.estimate(bounds, channels);
        check(a.problem() == null && a.shiftsAnything(), "Three machines must align");
        ClockAlignment.Channel ip1 = channel(a, "ip1"), ip2 = channel(a, "ip2");
        // P1 and P4 share a clock, so each remote clock is bounded from both sides: out via P1, back via P4.
        check(ip1.lower == -22680 && ip1.upper == -22294 && ip1.offset == -22487, "ip1: " + ip1.describe());
        check(ip2.lower == -3414 && ip2.upper == -2985 && ip2.offset == -3200, "ip2: " + ip2.describe());
        check(ip1.describe().equals("ip1 [P2_Place]: -22487 ms (bounds -22680..-22294 ms)"), ip1.describe());
        check(a.correction("P1_Place") == 0 && a.correction("P4_Place") == 0 && a.correction("P2_Place") == 22487
            && a.correction("P3_Place") == 3200 && a.correction("Unknown_Place") == 0 && a.correction(null) == 0, "Corrections");

        // On the ip0 clock every hop goes forward in time, and the fastest trip each way takes the same time.
        long fastestOut = Long.MAX_VALUE, fastestBack = Long.MAX_VALUE;
        for (long[][] branch : new long[][][] {p2, p3}) {
            String place = branch == p2 ? "P2_Place" : "P3_Place";
            for (long[] t : branch) {
                long out = a.toReference(place, t[2]) - a.toReference("P1_Place", t[1]);
                long back = a.toReference("P4_Place", t[4]) - a.toReference(place, t[3]);
                check(out > 0 && back > 0, t[0] + " still travels backwards in time: " + out + ", " + back);
                if (branch == p2) { fastestOut = Math.min(fastestOut, out); fastestBack = Math.min(fastestBack, back); }
            }
        }
        check(Math.abs(fastestOut - fastestBack) <= 1, "Midpoint must balance the fastest trips: " + fastestOut + " / " + fastestBack);

        // One machine: nothing moves.
        Map<String, String> single = new TreeMap<>(Map.of("P1_Place", "ip0", "P2_Place", "ip0", "P3_Place", "ip0", "P4_Place", "ip0"));
        ClockAlignment one = ClockAlignment.estimate(bounds, single);
        check(one.channels().isEmpty() && !one.shiftsAnything() && one.correction("P2_Place") == 0, "Single host must not shift");
        // Infrastructure unknown: nothing moves.
        check(!ClockAlignment.estimate(bounds, new TreeMap<>()).shiftsAnything(), "Unknown infrastructure must not shift");

        // Hops one way only: the smallest shift that keeps them forward in time, and none if they already are.
        Map<String, ClockOffsetBound> outOnly = PetriNetAnalyzer.clockOffsetBounds(
            List.of(new Hop(1, "P1_Place", "P2_Place", 10_000), new Hop(2, "P1_Place", "P3_Place", 10_000)),
            List.of(new Hop(1, null, "P2_Place", 7_000), new Hop(2, null, "P3_Place", 10_050)));
        ClockAlignment oneWay = ClockAlignment.estimate(outOnly, channels);
        check(channel(oneWay, "ip1").offset == -3000 && channel(oneWay, "ip2").offset == 0, "One-way: " + oneWay.channels().get(0).describe());
        check(channel(oneWay, "ip1").describe().contains("one-way hops only"), "One-way description");

        // Hops no single set of offsets can explain (a clock stepped mid-run): leave the timestamps as recorded.
        Map<String, ClockOffsetBound> stepped = PetriNetAnalyzer.clockOffsetBounds(
            List.of(new Hop(1, "P1_Place", "P2_Place", 10_000), new Hop(1, "P2_Place", "P4_Place", 20_000)),
            List.of(new Hop(1, null, "P2_Place", 9_000), new Hop(1, null, "P4_Place", 19_500)));
        ClockAlignment broken = ClockAlignment.estimate(stepped, channels);
        check(broken.problem() != null && !broken.shiftsAnything(), "A contradiction must not be aligned");

        // The place-to-channel map comes from the launcher's generated deployment rules.
        Path rules = Files.createTempFile("InfrastructureDeployment", ".ruleml.xml");
        try {
            Files.writeString(rules, "<Atom><Rel>boundChannel</Rel><Ind>ip1</Ind><Ind>192.168.1.152</Ind></Atom>\n"
                + "<Atom><Rel>localDefined</Rel><Ind>P2_Place</Ind></Atom>\n"
                + "<Atom><Rel>activeService</Rel><Ind>P1_Place</Ind><Ind>processToken</Ind><Ind>ip0</Ind><Ind>4001</Ind></Atom>\n"
                + "<Atom><Rel>activeService</Rel><Ind>P2_Place</Ind><Ind>processToken</Ind><Ind>ip1</Ind><Ind>4002</Ind></Atom>\n");
            check(ClockAlignment.placeChannels(rules).equals(Map.of("P1_Place", "ip0", "P2_Place", "ip1")), "Parsed " + ClockAlignment.placeChannels(rules));
            check(ClockAlignment.placeChannels(rules.resolveSibling("missing.xml")).isEmpty(), "A missing file means unknown");
        } finally {
            Files.deleteIfExists(rules);
        }
        System.out.println("PASS: " + checks + " clock alignment checks");
    }

    private static Map<String, String> placeChannels() {
        return new TreeMap<>(Map.of("P1_Place", "ip0", "P4_Place", "ip0", "P2_Place", "ip1", "P3_Place", "ip2"));
    }
    private static ClockAlignment.Channel channel(ClockAlignment a, String name) {
        for (ClockAlignment.Channel c : a.channels()) if (c.channel.equals(name)) return c;
        throw new AssertionError("No channel " + name);
    }
    private static void hops(long[][] rows, String place, List<Hop> sends, List<Hop> arrivals) {
        for (long[] t : rows) {
            int token = (int)t[0];
            sends.add(new Hop(token, "P1_Place", place, t[1]));
            arrivals.add(new Hop(token, null, place, t[2]));
            sends.add(new Hop(token, place, "P4_Place", t[3]));
            arrivals.add(new Hop(token, null, "P4_Place", t[4]));
        }
    }
}
