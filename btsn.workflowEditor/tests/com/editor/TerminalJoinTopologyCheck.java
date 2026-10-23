package com.editor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Keeps the user's exact terminal-join fixture authoritative. This only checks
 * the input topology; it does not claim that the runtime implements terminal joins.
 */
public final class TerminalJoinTopologyCheck {
    public static void main(String[] args) throws Exception {
        Path fixture = new File(args[0],
            "btsn.workflowEditor/tests/fixtures/terminal_join/mytest.json").toPath();
        JSONObject workflow = (JSONObject) new JSONParser().parse(Files.readString(fixture));
        Map<String, JSONObject> elements = new HashMap<>();
        for (Object raw : (JSONArray) workflow.get("elements")) {
            JSONObject element = (JSONObject) raw;
            elements.put((String) element.get("id"), element);
        }
        Set<String> edges = new HashSet<>();
        for (Object raw : (JSONArray) workflow.get("arrows")) {
            JSONObject edge = (JSONObject) raw;
            edges.add(edge.get("source") + "->" + edge.get("target"));
        }
        String[] required = {
            "EG1->T2", "T2->P3", "P3->T4", "T4->T5",
            "EG1->T7", "T7->P6", "P6->T8", "T8->T5", "T5->T9"
        };
        check(edges.size() == required.length, "Unexpected arc count: " + edges);
        for (String edge : required) check(edges.contains(edge), "Missing arc: " + edge);
        check("JoinNode".equals(elements.get("T5").get("node_type")),
            "T5 must be a JoinNode");
        check("TerminateNode".equals(elements.get("T9").get("node_type")),
            "T9 must be a TerminateNode");
        check("Other".equals(elements.get("T5").get("transition_type")),
            "The T5 join is topology-only, not an input to a service");
        check("Other".equals(elements.get("T9").get("transition_type")),
            "T9 is a topology-only termination");
        check(elements.size() == 9 && !elements.containsKey("P7"),
            "An artificial downstream service/place was introduced");
        check("StochasticService".equals(elements.get("P3").get("service"))
            && "P6Service".equals(elements.get("P6").get("service")),
            "Changed the user's actual services");
        check("v001".equals(elements.get("EG1").get("token_version")),
            "Changed event-generator version");
        check("1000".equals(elements.get("EG1").get("generator_rate")),
            "Changed event-generator interval");
        check(edges.stream().filter(e -> e.endsWith("->T5")).count() == 2,
            "Terminal join must have two incoming branches");
        check(edges.stream().filter(e -> e.startsWith("T5->")).count() == 1,
            "Terminal join must have exactly the one declared outgoing arc to T9");
        System.out.println("PASS: exact mytest topology: EG1 forks to P3/P6; "
            + "T4 and T8 enter T5 JoinNode; T5 -> T9 TerminateNode; "
            + "no synthetic service, shortcut arc or fallback destination");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
