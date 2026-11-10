package org.btsn.invocation;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * How a REST placement calls an API that was not written for RPSO: the placement's optional "call" object.
 * Without one, the host sends RPSO's own request and expects RPSO's own answer, as before.
 *
 * <pre>
 * "call": {
 *   "method":  "POST",                                    POST (default), PUT, PATCH, GET or DELETE
 *   "headers": {"Authorization": "Bearer ${env:PAY_KEY}"},
 *   "body":    "data",                                    "data" (default): the token's data as it is
 *                                                         "rpso": RPSO's own request
 *                                                         or a JSON template: {"amount": "${data.amount}"}
 *   "result":  "",                                        the part of the answer to publish; "" = all of it
 *   "route":   {"field": "status", "values": {"succeeded": "paid"}, "otherwise": "declined"},
 *   "statusRoutes": {"402": "declined"}                   an HTTP status that is an answer, not a failure
 * }
 * </pre>
 *
 * A value in braces, such as ${data.amount}, may appear in the endpoint, a header or the body:
 * <ul>
 * <li>${data.x}: field x of the token's data (the first input's data);</li>
 * <li>${name.x}: field x of the input called name, e.g. ${paymentResult.status};</li>
 * <li>${sequenceId} and ${version}: the call's own;</li>
 * <li>${env:NAME}: the environment variable NAME on the host, for keys that must not be written down.</li>
 * </ul>
 * The published result is the answer (or its "result" part) with "http_status" and, when a route is set,
 * "routing_decision": {"routing_path": ...} added, under the catalogue's return attribute, so T_out and the
 * analysis see it exactly as they see any other service's result.
 */
public final class CallMapping {
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList("POST", "PUT", "PATCH", "GET", "DELETE"));
    private static final Set<String> RESTRICTED = new HashSet<>(Arrays.asList(
            "connection", "content-length", "expect", "host", "upgrade"));
    private static final Pattern VALUE = Pattern.compile("\\$\\{([^}]+)\\}");

    final String method;
    final Map<String, String> headers = new LinkedHashMap<>();
    final Object body;            // "data", "rpso", or a JSON template (object or array)
    final String result;          // a path in the answer; "" = the whole answer
    final String routeField;      // a path in the answer, or null
    final Map<String, String> routeValues = new LinkedHashMap<>();
    final String otherwise;       // the route when the field's value is not listed; null = fail the call
    final Map<Integer, String> statusRoutes = new LinkedHashMap<>();

    private CallMapping(JSONObject call) {
        Object m = call.get("method");
        method = m == null ? "POST" : m.toString().trim().toUpperCase();
        require(METHODS.contains(method), "call.method must be one of " + METHODS + ", not " + m);
        Object h = call.get("headers");
        if (h != null) {
            require(h instanceof JSONObject, "call.headers must be an object of name: value");
            for (Object key : ((JSONObject) h).keySet()) {
                String name = key.toString().trim();
                require(name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+"), "call.headers has an invalid name: " + key);
                require(!RESTRICTED.contains(name.toLowerCase()), "call.headers cannot set " + name + "; the host sets it");
                Object value = ((JSONObject) h).get(key);
                require(value instanceof String, "call.headers." + name + " must be text");
                headers.put(name, (String) value);
            }
        }
        Object b = call.get("body");
        boolean sends = !method.equals("GET") && !method.equals("DELETE");
        if (b == null) body = sends ? "data" : null;
        else {
            require(sends, "A " + method + " call sends no body; remove call.body");
            require("data".equals(b) || "rpso".equals(b) || b instanceof JSONObject || b instanceof JSONArray,
                    "call.body must be \"data\", \"rpso\" or a JSON template");
            body = b;
        }
        Object r = call.get("result");
        result = r == null ? "" : r.toString().trim();
        Object route = call.get("route");
        if (route == null) { routeField = null; otherwise = null; }
        else {
            require(route instanceof JSONObject, "call.route must be an object");
            JSONObject o = (JSONObject) route;
            Object field = o.get("field");
            require(field instanceof String && !field.toString().trim().isEmpty(), "call.route needs a field: where the answer says what happened");
            routeField = field.toString().trim();
            Object values = o.get("values");
            if (values != null) {
                require(values instanceof JSONObject, "call.route.values must map the field's values to routes");
                for (Object key : ((JSONObject) values).keySet())
                    routeValues.put(key.toString(), path(((JSONObject) values).get(key), "call.route.values." + key));
            }
            Object other = o.get("otherwise");
            otherwise = other == null ? null : path(other, "call.route.otherwise");
            require(!routeValues.isEmpty() || otherwise != null, "call.route needs values, otherwise, or both");
        }
        Object statuses = call.get("statusRoutes");
        if (statuses != null) {
            require(statuses instanceof JSONObject, "call.statusRoutes must map HTTP statuses to routes");
            for (Object key : ((JSONObject) statuses).keySet()) {
                require(key.toString().matches("[1-5][0-9][0-9]"), "call.statusRoutes has an invalid HTTP status: " + key);
                statusRoutes.put(Integer.parseInt(key.toString()), path(((JSONObject) statuses).get(key), "call.statusRoutes." + key));
            }
        }
        for (Object key : call.keySet())
            require(Arrays.asList("method", "headers", "body", "result", "route", "statusRoutes").contains(key.toString()),
                    "call has an unknown setting: " + key);
    }

    /** The placement's mapping, or null when it has none (RPSO's own request and answer). */
    public static CallMapping parse(Object call) {
        if (call == null) return null;
        require(call instanceof JSONObject, "A placement's call must be an object");
        return new CallMapping((JSONObject) call);
    }

    /** True when the mapping sends RPSO's own request. */
    boolean sendsRpsoRequest() { return "rpso".equals(body); }

    /** What a call's values are drawn from: its named inputs, in order, and its identity. */
    static final class Values {
        final List<String> names; final List<String> inputs; final String sequenceId; final String version;
        Values(List<String> names, List<String> inputs, String sequenceId, String version) {
            this.names = names; this.inputs = inputs;
            this.sequenceId = sequenceId == null ? "" : sequenceId; this.version = version == null ? "" : version;
        }
        /** The input's value: its JSON, with a one-key wrapper of its own name removed ({"paymentResult": {...}}). */
        Object input(int i) {
            String text = i < inputs.size() ? inputs.get(i) : null;
            Object value = json(text);
            if (value == MISSING) return text;   // plain text, or no value
            if (value instanceof JSONObject && ((JSONObject) value).size() == 1 && ((JSONObject) value).containsKey(names.get(i)))
                value = ((JSONObject) value).get(names.get(i));
            return value;
        }
        /** The token's data: the first input's "data" when it is a token, otherwise the first input itself. */
        Object data() {
            if (names.isEmpty()) return new JSONObject();
            Object first = input(0);
            if (first instanceof JSONObject && ((JSONObject) first).get("data") != null) return ((JSONObject) first).get("data");
            return first;
        }
        Object lookup(String expression) {
            if (expression.startsWith("env:")) {
                String name = expression.substring(4).trim();
                String value = System.getenv(name);
                if (value == null) throw new IllegalStateException("environment variable " + name + " is not set on this host");
                return value;
            }
            if (expression.equals("sequenceId")) return sequenceId;
            if (expression.equals("version")) return version;
            String[] parts = expression.split("\\.", 2);
            int index = names.indexOf(parts[0]);
            Object root = index >= 0 ? input(index) : parts[0].equals("data") ? data() : MISSING;
            Object value = root == MISSING ? MISSING : parts.length == 1 ? root : at(root, parts[1]);
            if (value == MISSING) throw new IllegalStateException("this call has no value for ${" + expression + "}");
            return value;
        }
    }

    /** The endpoint with its ${...} values filled in, each encoded for a URL. */
    String url(String endpoint, Values values) {
        Matcher m = VALUE.matcher(endpoint);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(
                URLEncoder.encode(text(values.lookup(m.group(1).trim())), StandardCharsets.UTF_8).replace("+", "%20")));
        m.appendTail(out);
        return out.toString();
    }

    Map<String, String> headers(Values values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> h : headers.entrySet()) out.put(h.getKey(), fill(h.getValue(), values).toString());
        return out;
    }

    /** The request body, or null for a call that sends none. */
    String body(Values values, String rpsoRequest) {
        if (body == null) return null;
        if ("rpso".equals(body)) return rpsoRequest;
        if ("data".equals(body)) return jsonText(values.data());
        return jsonText(fill(body, values));
    }

    /** True when this HTTP status is an answer to publish rather than a failure. */
    boolean answers(int status) { return status / 100 == 2 || statusRoutes.containsKey(status); }

    /** The answer as RPSO publishes it: {returnAttribute: {..., "http_status": n, "routing_decision": ...}}. */
    @SuppressWarnings("unchecked")
    String publish(int status, String text, String returnAttribute) {
        Object answer = json(text);
        if (answer == MISSING) answer = text == null || text.isEmpty() ? new JSONObject() : textObject(text);
        Object part = result.isEmpty() ? answer : at(answer, result);
        if (part == MISSING) throw new IllegalStateException("the answer has no " + result + ": " + text);
        JSONObject published = new JSONObject();
        if (part instanceof JSONObject) published.putAll((JSONObject) part);
        else published.put("value", part);
        published.put("http_status", (long) status);
        String route = statusRoutes.get(status);
        if (route == null && routeField != null) {
            Object value = at(answer, routeField);
            String said = value == MISSING || value == null ? null : text(value);
            route = said != null && routeValues.containsKey(said) ? routeValues.get(said) : otherwise;
            if (route == null)
                throw new IllegalStateException("the answer's " + routeField + " was " + (said == null ? "missing" : "\"" + said + "\"")
                        + ", which no route names: " + text);
        }
        if (route != null) {
            JSONObject decision = new JSONObject();
            decision.put("routing_path", route);
            published.put("routing_decision", decision);
        }
        JSONObject out = new JSONObject();
        out.put(returnAttribute, published);
        return out.toJSONString();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private static final Object MISSING = new Object();

    /** A copy of a template with each ${...} filled in: a whole-string value keeps its JSON type. */
    @SuppressWarnings("unchecked")
    private static Object fill(Object template, Values values) {
        if (template instanceof JSONObject) {
            JSONObject out = new JSONObject();
            for (Object key : ((JSONObject) template).keySet()) out.put(key, fill(((JSONObject) template).get(key), values));
            return out;
        }
        if (template instanceof JSONArray) {
            JSONArray out = new JSONArray();
            for (Object item : (JSONArray) template) out.add(fill(item, values));
            return out;
        }
        if (!(template instanceof String)) return template;
        String s = (String) template;
        Matcher whole = VALUE.matcher(s);
        if (whole.matches()) return values.lookup(whole.group(1).trim());
        Matcher m = VALUE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(text(values.lookup(m.group(1).trim()))));
        m.appendTail(out);
        return out.toString();
    }

    /** The value at a dotted path (a.b.0.c); a text value holding JSON is read through, as original_token is. */
    static Object at(Object root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current instanceof String) { Object parsed = json((String) current); if (parsed != MISSING) current = parsed; }
            if (current instanceof JSONObject && ((JSONObject) current).containsKey(part)) current = ((JSONObject) current).get(part);
            else if (current instanceof JSONArray && part.matches("[0-9]+") && Integer.parseInt(part) < ((JSONArray) current).size())
                current = ((JSONArray) current).get(Integer.parseInt(part));
            else return MISSING;
        }
        return current;
    }

    private static Object json(String text) {
        if (text == null) return MISSING;
        String t = text.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) return MISSING;
        try { return new JSONParser().parse(t); } catch (Exception e) { return MISSING; }
    }

    @SuppressWarnings("unchecked")
    private static JSONObject textObject(String text) {
        JSONObject o = new JSONObject();
        o.put("text", text);
        return o;
    }

    private static String text(Object value) {
        return value == null ? "" : value instanceof JSONObject || value instanceof JSONArray ? jsonText(value) : value.toString();
    }

    private static String jsonText(Object value) {
        if (value == null) return "null";
        if (value instanceof JSONObject) return ((JSONObject) value).toJSONString();
        if (value instanceof JSONArray) return ((JSONArray) value).toJSONString();
        if (value instanceof String) return "\"" + JSONObject.escape((String) value) + "\"";
        return String.valueOf(value);
    }

    private static String path(Object value, String where) {
        require(value instanceof String && !value.toString().trim().isEmpty(), where + " must name a route");
        return value.toString().trim();
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    /** The names of the ${...} values in an endpoint, header or template, for checks before a run. */
    public static List<String> placeholders(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = VALUE.matcher(text == null ? "" : text);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }
}
