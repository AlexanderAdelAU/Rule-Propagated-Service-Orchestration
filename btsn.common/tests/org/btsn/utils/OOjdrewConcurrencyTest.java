package org.btsn.utils;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression check for issue #15: one OOjdrewAPI keeps querying its rule base while another
 * instance in the same JVM keeps parsing a different one. jDREW's SymbolTable is static, so
 * before the fix the second parse reset the symbols the first instance's query depended on and
 * the query returned no solutions (in the host, a wrongly terminated token).
 */
public class OOjdrewConcurrencyTest {
    private static final int FACTS = 40;
    private static final int ROUNDS = 300;
    private static final String QUERY =
        "<Query><Atom><Rel>activeService</Rel><Var>s</Var><Var>o</Var><Var>c</Var><Var>p</Var></Atom></Query>";

    public static void main(String[] args) throws Exception {
        String queried = ruleBase("Queried", 5000);
        String reparsed = ruleBase("Reparsed", 6000);

        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger parses = new AtomicInteger();
        Thread parser = new Thread(() -> {
            OOjdrewAPI other = new OOjdrewAPI();
            while (!done.get()) {
                other.parseKnowledgeBase(reparsed, false);
                parses.incrementAndGet();
            }
        });

        OOjdrewAPI api = new OOjdrewAPI();
        api.parseKnowledgeBase(queried, false);
        require(countSolutions(api) == FACTS, "single-threaded query should return " + FACTS + " solutions");

        parser.start();
        Map<Integer, Integer> counts = new TreeMap<>();
        int wrong = 0;
        for (int round = 0; round < ROUNDS; round++) {
            int n;
            try {
                n = countSolutions(api);
            } catch (RuntimeException e) {
                n = -1;
            }
            counts.merge(n, 1, Integer::sum);
            if (n != FACTS) wrong++;
        }
        done.set(true);
        parser.join();

        require(parses.get() >= 10, "parser thread did not run concurrently (" + parses.get() + " parses)");
        require(wrong == 0, wrong + " of " + ROUNDS + " concurrent queries were wrong; solution counts " + counts);
        System.out.println("OOjDREW concurrency check passed: " + ROUNDS + " queries, " + parses.get()
            + " concurrent parses, every query returned " + FACTS + " solutions");
    }

    private static int countSolutions(OOjdrewAPI api) {
        api.issueRuleMLQuery(QUERY);
        if (api.rowsReturned == 0) return 0;
        int n = 1;
        while (api.hasNext) {
            api.nextSolution();
            n++;
        }
        return n;
    }

    private static String ruleBase(String prefix, int basePort) {
        StringBuilder kb = new StringBuilder("<Assert>\n<Rulebase mapClosure=\"universal\">\n");
        for (int i = 0; i < FACTS; i++) {
            kb.append("<Atom><Rel>activeService</Rel><Ind>").append(prefix).append("_Place").append(i)
              .append("</Ind><Ind>").append(prefix).append("Operation</Ind><Ind>ip0</Ind><Ind>")
              .append(basePort + i).append("</Ind></Atom>\n");
        }
        return kb.append("</Rulebase>\n</Assert>\n").toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("OOjDREW concurrency check failed: " + message);
    }
}
