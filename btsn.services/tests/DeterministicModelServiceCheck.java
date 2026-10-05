import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** Check the JAR contract with both outcomes and unrelated token data. */
public class DeterministicModelServiceCheck {
    public static void main(String[] args) throws Exception {
        Object booleanService = Class.forName("org.btsn.services.BooleanTokenService").getConstructor().newInstance();
        Method route = booleanService.getClass().getMethod("processToken", String.class);
        Object forwardService = Class.forName("org.btsn.services.ForwardTokenService").getConstructor().newInstance();
        Method forward = forwardService.getClass().getMethod("processToken", String.class);
        for (String outcome : new String[]{"true", "false"}) {
            String input = "{\"id\":\"example\",\"data\":{\"outcome\":\"" + outcome + "\",\"value\":42}}";
            String response = (String) route.invoke(booleanService, input);
            JSONObject token = (JSONObject) ((JSONObject) new JSONParser().parse(response)).get("token");
            check(Boolean.valueOf(outcome).equals(token.get("outcome")), "Selected outcome changed");
            check(outcome.equals(((JSONObject) token.get("routing_decision")).get("routing_path")), "Routing outcome changed");
            check("example".equals(token.get("id")), "Token identity lost");
            check(Long.valueOf(42).equals(((JSONObject) token.get("data")).get("value")), "Token data lost");
            JSONObject carried = (JSONObject) ((JSONObject) new JSONParser().parse((String) forward.invoke(forwardService, token.toJSONString()))).get("token");
            check(token.equals(carried), "Forward operation changed token data");
            check(!token.containsKey("executionTime") && !token.containsKey("metrics"), "Service invented timing");
        }
        try {
            route.invoke(booleanService, "{\"outcome\":\"random\"}");
            throw new AssertionError("Invalid outcome was accepted");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalArgumentException, "Unexpected validation error");
        }
        System.out.println("PASS: true/false, token preservation and invalid outcome checked using packaged services");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
