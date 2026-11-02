package org.btsn.derby.Analysis;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The balance of a resource that one process opens and either the same process settles or a separate compensation
 * process undoes: a place invariant across the two processes, checked from the recorded transition firings alone.
 *
 * Think of the resource as a place R per workflow. A firing of the opening transition puts a token in R; a firing of the
 * settling transition, in the same workflow, takes it out; so does a firing of the undoing transition with the given
 * arc value in the compensation process, whose workflow k compensates workflow k of the first process (the loan
 * process's Signal node launches workflow 1030000 of v001 as 2030000 of v002). Once both processes have run, R must be
 * empty for every workflow and never have gone below empty: each resource opened is settled or undone exactly once.
 *
 * Arguments (name=value): resource="credit enquiry" opened=v001:T_out_P2 settled=v001:T_out_P4:!abandoned
 *   undone=v002:T_out_P2:withdrawn [refused=v002:T_out_P2:too_early]
 * A transition counts when it fired normally (EXIT, FORK or TERMINATE), not when the token failed there; with an arc
 * value, only firings with that value count, and with !value only those without it (underwriting settles an
 * application with any decision except abandoned, which instead signals compensation).
 */
public final class CompensationBalance {
    private static final String DB_URL = "jdbc:derby:./ServiceAnalysisDataBase;create=false";

    /** A transition in one version, optionally restricted to one arc value. */
    static final class Firing {
        final int base; final String transition; final String arc; final boolean except;
        Firing(String spec) {
            String[] p = spec.split(":", 3);
            if (p.length < 2) throw new IllegalArgumentException("Expected version:transition[:arc], got " + spec);
            base = Integer.parseInt(p[0].replaceAll("\\D", "")) * 1_000_000;
            transition = p[1];
            except = p.length > 2 && p[2].startsWith("!");
            arc = p.length > 2 ? (except ? p[2].substring(1) : p[2]) : null;
        }
        boolean counts(String arcValue) { return arc == null || (except != arc.equals(arcValue)); }
        @Override public String toString() { return "v" + String.format("%03d", base / 1_000_000) + " " + transition + (arc == null ? "" : " [" + (except ? "not " : "") + arc + "]"); }
    }

    /** The root of a token's workflow: 1030001 belongs to workflow 1030000. */
    static int workflowOf(int tokenId) { return tokenId - tokenId % 10_000; }

    /** The workflow of the first process that a compensation workflow compensates: the same number in the first version. */
    static int audited(int compensationWorkflow, int compensationBase, int auditedBase) {
        return auditedBase + (compensationWorkflow - compensationBase);
    }

    /** The result for each workflow of the first process. */
    static final class Balance {
        final Map<Integer, int[]> byWorkflow = new TreeMap<>();   // workflow -> {opened, settled, undone, refused}
        int[] of(int workflow) { return byWorkflow.computeIfAbsent(workflow, k -> new int[4]); }
        List<Integer> where(java.util.function.Predicate<int[]> test) {
            List<Integer> out = new ArrayList<>();
            byWorkflow.forEach((w, c) -> { if (test.test(c)) out.add(w); });
            return out;
        }
    }

    /** Counts the firings, per workflow of the first process. */
    static Balance balance(Connection conn, Firing opened, Firing settled, Firing undone, Firing refused) throws SQLException {
        Balance b = new Balance();
        count(conn, opened, b, 0, opened.base);
        count(conn, settled, b, 1, opened.base);
        count(conn, undone, b, 2, opened.base);
        if (refused != null) count(conn, refused, b, 3, opened.base);
        return b;
    }

    /**
     * One count per recorded firing. Each token passing a transition records one firing, so the transitions named should
     * be ones a workflow's token passes alone (not a T_out that forks).
     */
    private static void count(Connection conn, Firing f, Balance b, int column, int auditedBase) throws SQLException {
        String sql = "SELECT tokenId, eventType, arcValue FROM CONSOLIDATED_MARKING_EVOLUTION "
            + "WHERE workflowBase = ? AND transitionId = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, f.base);
            ps.setString(2, f.transition);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("eventType");
                    if (!"EXIT".equals(type) && !"FORK".equals(type) && !"TERMINATE".equals(type)) continue;
                    if (!f.counts(rs.getString("arcValue"))) continue;
                    int workflow = workflowOf(rs.getInt("tokenId"));
                    if (f.base != auditedBase) workflow = audited(workflow, f.base, auditedBase);
                    b.of(workflow)[column]++;
                }
            }
        }
    }

    static String report(String resource, Firing opened, Firing settled, Firing undone, Firing refused, Balance b) {
        StringBuilder out = new StringBuilder();
        out.append("\n=== COMPENSATION BALANCE: ").append(resource).append(" ===\n");
        out.append("Opened by ").append(opened).append("; settled by ").append(settled).append("; undone by ").append(undone)
           .append(refused == null ? "" : "; refused as " + refused).append("\n");
        out.append("A ").append(resource).append(" opened must be settled or undone exactly once (a place invariant across both processes).\n\n");
        List<Integer> withResource = b.where(c -> c[0] > 0);
        List<Integer> settledOnly = b.where(c -> c[0] > 0 && c[1] > 0 && c[2] == 0);
        List<Integer> undoneOnly = b.where(c -> c[0] > 0 && c[1] == 0 && c[2] == 1);
        List<Integer> leftBehind = b.where(c -> c[0] > 0 && c[1] == 0 && c[2] == 0);
        List<Integer> undoneAlthoughSettled = b.where(c -> c[1] > 0 && c[2] > 0);
        List<Integer> undoneTwice = b.where(c -> c[2] > 1);
        List<Integer> undoneWithout = b.where(c -> c[0] == 0 && c[2] > 0);
        List<Integer> openedTwice = b.where(c -> c[0] > 1);
        List<Integer> refusedEarly = b.where(c -> c[3] > 0);
        out.append(String.format("   Workflows with a %-18s %d%n", resource + ":", withResource.size()));
        out.append(String.format("   Settled by the process:           %d%n", settledOnly.size()));
        out.append(String.format("   Undone by compensation:           %d%s%n", undoneOnly.size(), list(undoneOnly)));
        out.append(String.format("   Left behind (neither):            %d%s%n", leftBehind.size(), list(leftBehind)));
        out.append(String.format("   Undone although settled:          %d%s%n", undoneAlthoughSettled.size(), list(undoneAlthoughSettled)));
        out.append(String.format("   Undone more than once:            %d%s%n", undoneTwice.size(), list(undoneTwice)));
        out.append(String.format("   Undone without being opened:      %d%s%n", undoneWithout.size(), list(undoneWithout)));
        out.append(String.format("   Opened more than once:            %d%s%n", openedTwice.size(), list(openedTwice)));
        if (refused != null)
            out.append(String.format("   Compensation refused (too early): %d%s%n", refusedEarly.size(), list(refusedEarly)));
        boolean ok = leftBehind.isEmpty() && undoneAlthoughSettled.isEmpty() && undoneTwice.isEmpty() && undoneWithout.isEmpty() && openedTwice.isEmpty();
        out.append("   Balance:                          ").append(ok
            ? "[OK] every " + resource + " opened was settled or undone, exactly once"
            : "[VIOLATION] see the workflows listed above").append("\n");
        out.append("=== END COMPENSATION BALANCE ===\n");
        return out.toString();
    }

    private static String list(List<Integer> workflows) {
        if (workflows.isEmpty()) return "";
        StringBuilder s = new StringBuilder(" (workflows");
        for (int i = 0; i < workflows.size() && i < 12; i++) s.append(' ').append(workflows.get(i));
        return s.append(workflows.size() > 12 ? " ...)" : ")").toString();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new LinkedHashMap<>();
        for (String arg : args) { int eq = arg.indexOf('='); if (eq > 0) a.put(arg.substring(0, eq), arg.substring(eq + 1)); }
        String resource = a.getOrDefault("resource", "resource");
        Firing opened = new Firing(required(a, "opened")), settled = new Firing(required(a, "settled")), undone = new Firing(required(a, "undone"));
        Firing refused = a.containsKey("refused") ? new Firing(a.get("refused")) : null;
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            System.out.print(report(resource, opened, settled, undone, refused, balance(conn, opened, settled, undone, refused)));
        } finally {
            try { DriverManager.getConnection("jdbc:derby:;shutdown=true"); } catch (SQLException expected) { }
        }
    }

    private static String required(Map<String, String> a, String name) {
        String v = a.get(name);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException("Missing " + name + "=");
        return v;
    }
}
