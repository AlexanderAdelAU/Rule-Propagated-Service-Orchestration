import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.btsn.invocation.BooleanTokenAdapter;
import org.btsn.rulecontroller.RuleDeployer;
import org.btsn.rulecontroller.TopologyBindingGenerator;

/**
 * What a place must declare, and what a routing-only place does:
 * - a place on the token path with no service is rejected by both compilers; an unconnected place (a label) is ignored;
 * - a place may expose several operations, and the canonical bindings generated for its service instance keep every one;
 * - a place bound to AlwaysTrueService only routes: the token's data passes through unchanged, under the name the
 *   deployment gives each hop, however many such places it crosses.
 * Run with a scratch working directory: the binding generator writes to ../btsn.common/ServiceAttributeBindings.
 */
public final class PlaceContractCheck {
    public static void main(String[] args) throws Exception {
        Logger.getRootLogger().setLevel(Level.OFF);
        Path scratch = Path.of("").toAbsolutePath().getParent();

        // 1. A connected place with no service is rejected; an unconnected one (a label) is not.
        String noService = "{\"processType\":\"PetriNet\",\"elements\":["
            + transition("T_in_R", "T_in") + "," + place("R", "\"service\":\"\",\"operations\":[]") + "," + transition("T_out_R", "T_out") + "],"
            + "\"arrows\":[" + arrow("T_in_R", "R", null) + "," + arrow("R", "T_out_R", null) + "]}";
        String label = "{\"processType\":\"PetriNet\",\"elements\":["
            + transition("T_in_P", "T_in") + "," + place("P", service("AlwaysTrueService", op("processToken", "token", "token"))) + ","
            + transition("T_out_P", "T_out") + "," + place("Legend", "\"service\":\"\",\"operations\":[]") + "],"
            + "\"arrows\":[" + arrow("T_in_P", "P", null) + "," + arrow("P", "T_out_P", null) + "]}";
        for (Object compiler : compilers()) {
            String name = compiler.getClass().getSimpleName();
            try {
                parse(compiler, noService);
                fail(name + " accepted a connected place with no service");
            } catch (InvocationTargetException rejected) {
                String message = String.valueOf(rejected.getCause().getMessage());
                check(message.contains("Place 'R' is connected but names no service operation")
                    && message.contains("AlwaysTrueService"), name + " rejected it without saying why: " + message);
            }
            parse(compiler, label); // must not throw
        }

        // 2. A place with two operations keeps both, from parsing to the generated binding file.
        String treatment = "{\"processType\":\"PetriNet\",\"elements\":["
            + transition("T_in_Treatment", "T_in") + ","
            + place("Treatment", service("TreatmentService",
                op("executeTreatmentPlan", "treatmentResults", "diagnosisResults") + ","
                + op("executeDirectTreatment", "treatmentResults", "triageResults"))) + ","
            + transition("T_out_Treatment", "T_out") + "],"
            + "\"arrows\":[" + arrow("T_in_Treatment", "Treatment", "executeDirectTreatment") + "," + arrow("Treatment", "T_out_Treatment", null) + "]}";
        RuleDeployer deployer = new RuleDeployer("fixture", "v001");
        parse(deployer, treatment);
        Field multi = RuleDeployer.class.getDeclaredField("multiOpServices"); multi.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, List<String>> operations = (Map<String, List<String>>)multi.get(deployer);
        check(Arrays.asList("executeTreatmentPlan", "executeDirectTreatment").equals(operations.get("Treatment")),
            "Rule deployer lost an operation: " + operations);
        TopologyBindingGenerator generator = new TopologyBindingGenerator("fixture", "v001");
        parse(generator, treatment);
        Method generate = TopologyBindingGenerator.class.getDeclaredMethod("generatePetriNetCanonicalBindings");
        generate.setAccessible(true);
        generate.invoke(generator);
        Path binding = scratch.resolve("btsn.common/ServiceAttributeBindings/TreatmentServiceInstance/TreatmentServiceInstance-CanonicalBindings.ruleml.xml");
        check(Files.isRegularFile(binding), "No binding file written at " + binding);
        String text = Files.readString(binding).replaceAll("<!--.*?-->", "").replaceAll("\\s+", "");
        check(text.contains("<Rel>canonicalBinding</Rel><Ind>executeTreatmentPlan</Ind><Ind>treatmentResults</Ind><Ind>diagnosisResults</Ind>"),
            "Binding lost executeTreatmentPlan:\n" + text);
        check(text.contains("<Rel>canonicalBinding</Rel><Ind>executeDirectTreatment</Ind><Ind>treatmentResults</Ind><Ind>triageResults</Ind>"),
            "Binding lost executeDirectTreatment:\n" + text);

        // 3. A chain of routing-only places carries the token's data through unchanged.
        String data = "{\"triageResults\":{\"patient\":\"A-17\",\"priority\":2,\"notes\":[\"chest pain\",\"ECG ordered\"]}}";
        String token = data;
        for (String output : new String[] { "token", "token", "token_branch2" })
            token = BooleanTokenAdapter.invoke("org.btsn.services.AlwaysTrueService", "processToken", List.of(token), output);
        org.json.simple.JSONObject envelope = (org.json.simple.JSONObject)new org.json.simple.parser.JSONParser().parse(token);
        check(envelope.size() == 1 && envelope.containsKey("token_branch2"), "Last hop's name not used: " + token);
        org.json.simple.JSONObject result = (org.json.simple.JSONObject)envelope.get("token_branch2");
        org.json.simple.JSONArray carried = (org.json.simple.JSONArray)result.get("data");
        check(Boolean.TRUE.equals(result.get("decision")), "A routing-only place did not route: " + token);
        check(carried.size() == 1 && carried.get(0).equals(new org.json.simple.parser.JSONParser().parse(data)),
            "Data changed or nested across three routing-only places: " + token);

        System.out.println("Place contract check passed: connected places need a service, labels are ignored, "
            + "both Treatment operations bound, data unchanged across three routing-only places");
    }

    private static List<Object> compilers() {
        return Arrays.asList(new RuleDeployer("fixture", "v001"), new TopologyBindingGenerator("fixture", "v001"));
    }
    private static void parse(Object compiler, String json) throws Exception {
        Method parse = compiler.getClass().getDeclaredMethod("parseJsonWorkflow", String.class);
        parse.setAccessible(true);
        parse.invoke(compiler, json.replace(":[", ": ["));
    }
    private static String transition(String id, String kind) {
        return "{\"id\":\"" + id + "\",\"type\":\"TRANSITION\",\"label\":\"" + id + "\",\"node_type\":\"EdgeNode\",\"node_value\":\"EDGE_NODE\",\"transition_type\":\"" + kind + "\"}";
    }
    private static String place(String id, String body) {
        return "{\"id\":\"" + id + "\",\"type\":\"PLACE\",\"label\":\"" + id + "\"," + body + "}";
    }
    private static String service(String name, String operations) {
        return "\"service\":\"" + name + "\",\"serviceInstance\":\"" + name + "Instance\",\"operations\":[" + operations + "]";
    }
    private static String op(String name, String returns, String argument) {
        return "{\"name\":\"" + name + "\",\"returnAttribute\":\"" + returns + "\",\"arguments\":[{\"name\":\"" + argument + "\"}]}";
    }
    private static String arrow(String source, String target, String endpoint) {
        return "{\"source\":\"" + source + "\",\"target\":\"" + target + "\"" + (endpoint == null ? "" : ",\"endpoint\":\"" + endpoint + "\"") + "}";
    }
    private static void check(boolean ok, String message) { if (!ok) fail(message); }
    private static void fail(String message) { throw new IllegalStateException("Place contract check failed: " + message); }
}
