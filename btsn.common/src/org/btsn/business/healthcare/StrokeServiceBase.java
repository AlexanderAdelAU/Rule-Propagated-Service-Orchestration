package org.btsn.business.healthcare;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * What the stroke code services share: the patient's case, which travels with the stroke code from the ambulance to
 * the stroke centre ("stroke_case": what the paramedics, the emergency department and the tests found), and the
 * workflow number the angiography suite hold is recorded against.
 *
 * The clinical rules in these services are simplified from the hyper-acute stroke pathway for this demonstration;
 * they are not clinical guidance.
 */
public abstract class StrokeServiceBase extends BaseBusinessService {

    /** The case carried by an input, or an empty one. */
    protected final JSONObject strokeCase(String input) {
        Object found = findValue(parseObject(input), "stroke_case");
        return found instanceof JSONObject ? (JSONObject) found : new JSONObject();
    }

    /** The case carried by any of a join's inputs, merged (each input carries the same case, with its own findings). */
    @SuppressWarnings("unchecked")
    protected final JSONObject strokeCase(String... inputs) {
        JSONObject merged = new JSONObject();
        for (String input : inputs) merged.putAll(strokeCase(input));
        return merged;
    }

    protected static double number(JSONObject strokeCase, String key, double defaultValue) {
        Object value = strokeCase.get(key);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected static boolean flag(JSONObject strokeCase, String key) {
        Object value = strokeCase.get(key);
        return value != null && Boolean.parseBoolean(value.toString());
    }

    protected static String text(JSONObject strokeCase, String key, String defaultValue) {
        Object value = strokeCase.get(key);
        return value == null ? defaultValue : value.toString();
    }

    /** The stroke code's workflow number, recorded by the ambulance's screen and carried on by every service. */
    protected final String workflowId(String input) {
        return textValue(input, "workflow_id", null);
    }

    /** A result carrying the case and workflow number on, with this service's findings and route. */
    @SuppressWarnings("unchecked")
    protected final String strokeResult(String attribute, JSONObject strokeCase, String workflowId, JSONObject findings, String route) {
        JSONObject result = new JSONObject();
        result.putAll(findings);
        result.put("stroke_code", true);
        result.put("stroke_case", strokeCase);
        if (workflowId != null) result.put("workflow_id", workflowId);
        if (route != null) result.put("routing_decision", routingDecision(route));
        return businessResult(attribute, result);
    }
}
