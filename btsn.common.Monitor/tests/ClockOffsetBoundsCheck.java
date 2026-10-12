import java.util.List;
import java.util.Map;
import org.btsn.derby.Analysis.PetriNetAnalyzer;
import org.btsn.derby.Analysis.PetriNetAnalyzer.ClockOffsetBound;
import org.btsn.derby.Analysis.PetriNetAnalyzer.Hop;

/** Clock-offset bounds between hosts from tokens sent between them, using the two-machine inventory run's records. */
public final class ClockOffsetBoundsCheck {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        // P1 on 192.168.1.82, P2 on 192.168.1.152. Sends: EXIT by the sender's clock; arrivals: recorded arrival and BUFFERED.
        List<Hop> sends = List.of(
            new Hop(1050000, "P1_Place", "P2_Place", 1791771696927L),
            new Hop(1060000, "P1_Place", "P2_Place", 1791771697898L),
            new Hop(1090000, "P1_Place", "P2_Place", 1791771700962L),
            new Hop(1050000, "P2_Place", "P1_Place", 1791771698438L),
            new Hop(1000000, "P1_Place", "P1_Place", 1791771691993L));
        List<Hop> arrivals = List.of(
            new Hop(1050000, null, "P2_Place", 1791771697034L), new Hop(1050000, null, "P2_Place", 1791771697830L),
            new Hop(1060000, null, "P2_Place", 1791771697954L), new Hop(1060000, null, "P2_Place", 1791771698734L),
            new Hop(1090000, null, "P2_Place", 1791771700950L), new Hop(1090000, null, "P2_Place", 1791771701152L),
            new Hop(1050000, null, "P1_Place", 1791771696852L), new Hop(1050000, null, "P1_Place", 1791771698729L));
        Map<String, ClockOffsetBound> bounds = PetriNetAnalyzer.clockOffsetBounds(sends, arrivals);
        check(bounds.size() == 1, "One pair of places: " + bounds.keySet());
        ClockOffsetBound b = bounds.get("P1_Place / P2_Place");
        // Order 10 was recorded arriving at P2 12 ms before P1 sent it; order 6 came back to P1 291 ms after P2 sent it.
        check(b.upper == -12 && b.lower == -291 && b.hopsAtoB == 3 && b.hopsBtoA == 1 && !b.contradictory(), "Bounds: " + b.describe());
        check(b.describe().equals("clock(P2_Place) - clock(P1_Place) between -291 and -12 ms (3 hops P1_Place->P2_Place, 1 back)"), b.describe());

        // One machine: the offset is zero, and the bounds must allow it.
        Map<String, ClockOffsetBound> same = PetriNetAnalyzer.clockOffsetBounds(
            List.of(new Hop(1, "P1_Place", "P2_Place", 1000), new Hop(1, "P2_Place", "P1_Place", 1500)),
            List.of(new Hop(1, null, "P2_Place", 1004), new Hop(1, null, "P1_Place", 1503)));
        ClockOffsetBound one = same.get("P1_Place / P2_Place");
        check(one.lower <= 0 && one.upper >= 0 && !one.contradictory(), "One clock must fit: " + one.describe());

        // Timestamps no fixed offset can explain are flagged.
        ClockOffsetBound bad = PetriNetAnalyzer.clockOffsetBounds(
            List.of(new Hop(1, "P1_Place", "P2_Place", 1000), new Hop(1, "P2_Place", "P1_Place", 1100)),
            List.of(new Hop(1, null, "P2_Place", 950), new Hop(1, null, "P1_Place", 1080))).get("P1_Place / P2_Place");
        check(bad.contradictory() && bad.describe().contains("[CHECK]"), "Contradiction not flagged: " + bad.describe());

        // Only one direction: an open bound.
        ClockOffsetBound open = PetriNetAnalyzer.clockOffsetBounds(
            List.of(new Hop(1, "P1_Place", "P3_Place", 1000)), List.of(new Hop(1, null, "P3_Place", 1040))).get("P1_Place / P3_Place");
        check(open.lower == null && open.upper == 40 && open.describe().contains("at most 40 ms"), open.describe());
        System.out.println("PASS: " + checks + " clock-offset bound checks");
    }
}
