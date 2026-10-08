package org.btsn.invocation;

import java.lang.reflect.Method;
import java.util.List;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** Maps a Boolean business result to the legacy token envelope; never chooses a destination. */
public final class BooleanTokenAdapter {
    private BooleanTokenAdapter() { }
    @SuppressWarnings("unchecked")
    public static String invoke(String implementation, String operation, List<String> inputs,
                                String outputAttribute) throws Exception {
        Class<?> type = Class.forName(implementation);
        Method method = type.getMethod(operation, String.class);
        if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class)
            throw new IllegalStateException("Boolean token adapter requires a Boolean service result");
        JSONArray data = new JSONArray();
        for (String input : inputs) {
            Object value;
            try { value = new JSONParser().parse(input); }
            catch (Exception invalidJson) { value = input; }
            carry(value, data);
        }
        boolean decision = (Boolean)method.invoke(type.getConstructor().newInstance(), data.toJSONString());
        return response(decision, data, outputAttribute);
    }
    @SuppressWarnings("unchecked")
    private static void carry(Object value, JSONArray data) {
        // Unwrap only this adapter's own envelope. Retry/loop processing must not nest prior transport results.
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            if ("boolean-token".equals(object.get("invocation_adapter")) && object.get("data") instanceof JSONArray) {
                for (Object original : (JSONArray)object.get("data")) carry(original, data);
                return;
            }
            if (object.size() == 1 && object.values().iterator().next() instanceof JSONObject) {
                JSONObject inner = (JSONObject)object.values().iterator().next();
                if ("boolean-token".equals(inner.get("invocation_adapter"))) { carry(inner, data); return; }
            }
        }
        // Fork copies of the same business value need only one carried copy; genealogy stays in infrastructure.
        if (!data.contains(value)) data.add(value);
    }
    @SuppressWarnings("unchecked")
    public static String response(boolean decision, JSONArray data, String outputAttribute) {
        JSONObject routing = new JSONObject();
        routing.put("routing_path", Boolean.toString(decision));
        routing.put("guard_result", decision);
        JSONObject result = new JSONObject();
        result.put("invocation_adapter", "boolean-token");
        result.put("decision", decision);
        result.put("routing_decision", routing);
        result.put("data", data);
        result.put("status", "COMPLETED");
        JSONObject envelope = new JSONObject();
        envelope.put(outputAttribute, result);
        return envelope.toJSONString();
    }
}
