package com.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Reads an API's published definition (OpenAPI 3, or Swagger 2, as JSON) and writes the endpoint and call mapping
 * for one of its operations, for the Deploy panel's Call mapping box.
 *
 * The definition says how the API is called: the address, the method, the fields of the request and whether a key
 * is needed. Those are filled in. It cannot say what its answers mean to the process, so the route is a
 * suggestion: the answer field that looks like an outcome, with each of its values routed to a path of the same
 * name, for the designer to change to the guards on the place's arcs.
 */
public final class OpenApiImport {
    private static final List<String> METHODS = Arrays.asList("post", "put", "patch", "get", "delete");
    private static final List<String> OUTCOME_FIELDS = Arrays.asList("status", "state", "result", "outcome", "decision");

    /** One operation of the API. */
    public static final class Operation {
        public final String method, path, summary;
        final JSONObject definition, pathItem;
        Operation(String method, String path, JSONObject definition, JSONObject pathItem) {
            this.method = method.toUpperCase(); this.path = path; this.definition = definition; this.pathItem = pathItem;
            Object s = definition.get("summary") != null ? definition.get("summary") : definition.get("operationId");
            summary = s == null ? "" : s.toString();
        }
        @Override public String toString() { return method + " " + path + (summary.isEmpty() ? "" : "  -  " + summary); }
    }

    /** What importing an operation gives: the endpoint, the mapping, and what the designer still has to check. */
    public static final class Mapping {
        public String endpoint = "";
        /** The call mapping, as indented JSON. */
        public String call = "";
        /** The fields of the token's data that the mapping reads: what the token format must carry. */
        public final List<String> dataFields = new ArrayList<>();
        /** Fields the mapping sends when the token's data has them and leaves out when it does not. */
        public final List<String> optionalFields = new ArrayList<>();
        /** What to check or finish, in plain words. */
        public final List<String> notes = new ArrayList<>();
    }

    private final JSONObject spec;

    public OpenApiImport(String json) {
        Object parsed;
        try { parsed = new JSONParser().parse(json); }
        catch (Exception | Error e) {
            throw new IllegalArgumentException("This is not a JSON file. An OpenAPI definition in YAML must be converted to JSON first.");
        }
        if (!(parsed instanceof JSONObject) || !(((JSONObject) parsed).get("paths") instanceof JSONObject)
                || (((JSONObject) parsed).get("openapi") == null && ((JSONObject) parsed).get("swagger") == null))
            throw new IllegalArgumentException("This is not an OpenAPI definition: it has no \"openapi\" (or \"swagger\") version and \"paths\".");
        spec = (JSONObject) parsed;
    }

    public String title() {
        Object info = spec.get("info");
        Object title = info instanceof JSONObject ? ((JSONObject) info).get("title") : null;
        return title == null ? "" : title.toString();
    }

    /** Every operation the definition lists, in its order. */
    public List<Operation> operations() {
        List<Operation> out = new ArrayList<>();
        JSONObject paths = (JSONObject) spec.get("paths");
        for (Object path : paths.keySet()) {
            Object item = resolve(paths.get(path));
            if (!(item instanceof JSONObject)) continue;
            for (String method : METHODS) {
                Object operation = ((JSONObject) item).get(method);
                if (operation instanceof JSONObject) out.add(new Operation(method, path.toString(), (JSONObject) operation, (JSONObject) item));
            }
        }
        return out;
    }

    /** The endpoint and call mapping for one operation. */
    public Mapping mapping(Operation operation) {
        Mapping m = new Mapping();
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("method", operation.method);

        // The address: the first server, then the path, its {values} read from the token's data.
        String server = server();
        if (server.isEmpty()) m.notes.add("The definition names no server: put the API's address in front of the path in Service endpoint.");
        StringBuilder endpoint = new StringBuilder(server + operation.path.replaceAll("\\{([^}]+)\\}", "\\${data.$1}"));
        java.util.regex.Matcher inPath = java.util.regex.Pattern.compile("\\{([^}]+)\\}").matcher(operation.path);
        while (inPath.find()) field(m, inPath.group(1));

        // Headers: the key the API needs, then the headers the operation requires.
        Map<String, Object> headers = new LinkedHashMap<>();
        String key = "API_KEY";
        for (JSONObject scheme : securitySchemes(operation)) {
            String type = text(scheme.get("type")), in = text(scheme.get("in")), name = text(scheme.get("name"));
            if ("http".equals(type) && "bearer".equalsIgnoreCase(text(scheme.get("scheme")))) headers.put("Authorization", "Bearer ${env:" + key + "}");
            else if ("http".equals(type) && "basic".equalsIgnoreCase(text(scheme.get("scheme")))) headers.put("Authorization", "Basic ${env:" + key + "}");
            else if ("apiKey".equals(type) && "header".equals(in) && !name.isEmpty()) headers.put(name, "${env:" + key + "}");
            else if ("apiKey".equals(type) && "query".equals(in) && !name.isEmpty()) query(endpoint, name, "${env:" + key + "}");
            else if ("oauth2".equals(type) || "openIdConnect".equals(type)) headers.put("Authorization", "Bearer ${env:" + key + "}");
            else continue;
            m.notes.add("The API needs a key. Set the environment variable " + key + " on each host's machine (and before starting the editor, to Try a call)"
                    + ("basic".equalsIgnoreCase(text(scheme.get("scheme"))) ? ", holding user:password encoded in Base64" : "")
                    + ("oauth2".equals(type) || "openIdConnect".equals(type) ? ", holding an access token you have obtained" : "") + ".");
            break;
        }
        List<String> optionalHeaders = new ArrayList<>();
        for (JSONObject parameter : parameters(operation)) {
            String in = text(parameter.get("in")), name = text(parameter.get("name"));
            boolean required = Boolean.TRUE.equals(parameter.get("required"));
            if ("query".equals(in) && required) { query(endpoint, name, "${data." + name + "}"); field(m, name); }
            else if ("header".equals(in) && required) { headers.put(name, "${data." + name + "}"); field(m, name); }
            else if ("header".equals(in) && name.equalsIgnoreCase("Idempotency-Key")) headers.put(name, "${sequenceId}");
            else if ("header".equals(in)) optionalHeaders.add(name);
        }
        if (!optionalHeaders.isEmpty()) m.notes.add("Optional headers left out: " + String.join(", ", optionalHeaders) + ".");
        if (!headers.isEmpty()) call.put("headers", headers);

        // The body: each field of the request, read from a field of the same name in the token's data.
        boolean sends = !operation.method.equals("GET") && !operation.method.equals("DELETE");
        JSONObject request = sends ? requestSchema(operation) : null;
        if (request != null) {
            Map<String, Object> body = template(request, "data", m, 0);
            if (body.isEmpty()) call.put("body", "data");
            else call.put("body", body);
        } else if (sends) call.put("body", "data");

        // The route: suggested from the answer's outcome field.
        JSONObject answer = answerSchema(operation);
        String outcome = null; List<String> values = new ArrayList<>();
        if (answer != null && answer.get("properties") instanceof JSONObject) {
            JSONObject properties = (JSONObject) answer.get("properties");
            for (String candidate : OUTCOME_FIELDS) if (properties.containsKey(candidate)) { outcome = candidate; break; }
            if (outcome == null) for (Object name : properties.keySet()) {
                Object p = resolve(properties.get(name));
                if (p instanceof JSONObject && ((JSONObject) p).get("enum") instanceof JSONArray) { outcome = name.toString(); break; }
            }
            if (outcome != null) {
                Object p = resolve(properties.get(outcome));
                if (p instanceof JSONObject && ((JSONObject) p).get("enum") instanceof JSONArray)
                    for (Object v : (JSONArray) ((JSONObject) p).get("enum")) values.add(String.valueOf(v));
            }
        }
        if (outcome != null) {
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("field", outcome);
            Map<String, Object> routes = new LinkedHashMap<>();
            for (String v : values) routes.put(v, v);
            if (!routes.isEmpty()) route.put("values", routes);
            else route.put("otherwise", "done");
            call.put("route", route);
            m.notes.add("Route: the answer's \"" + outcome + "\" picks the path"
                    + (values.isEmpty() ? "; the definition does not list its values, so every answer takes \"done\"" : " (" + String.join(", ", values) + ")")
                    + ". Change the path names to the guards on this place's arcs.");
        } else m.notes.add("No route was suggested: the definition's answer has no field that looks like an outcome. A place whose T_out is a gateway needs one; add \"route\".");

        // An HTTP status that carries an answer rather than an error (a 402 decline) may be a route.
        List<String> statuses = new ArrayList<>();
        Map<String, Object> statusRoutes = new LinkedHashMap<>();
        Object responses = operation.definition.get("responses");
        if (responses instanceof JSONObject) for (Object status : ((JSONObject) responses).keySet()) {
            String code = status.toString();
            if (!code.matches("[45][0-9][0-9]")) continue;
            statuses.add(code);
            JSONObject schema = schemaOf(resolve(((JSONObject) responses).get(status)));
            if (code.equals("402") || (schema != null && answer != null && schema == answer)) statusRoutes.put(code, values.contains("declined") ? "declined" : "status" + code);
        }
        if (!statusRoutes.isEmpty()) {
            call.put("statusRoutes", statusRoutes);
            m.notes.add("HTTP " + String.join(", ", statusRoutes.keySet()) + " carries an answer, so it is a route, not a failed call. Check its path name.");
        }
        statuses.removeAll(statusRoutes.keySet());
        if (!statuses.isEmpty()) m.notes.add("Other documented errors (HTTP " + String.join(", ", statuses) + ") fail the call at the place, with the API's answer as the reason.");

        if (!m.optionalFields.isEmpty()) m.notes.add(0, "Optional, sent only when the token's data has them: " + String.join(", ", m.optionalFields) + ".");
        if (!m.dataFields.isEmpty()) m.notes.add(0, "The token's data must carry: " + String.join(", ", m.dataFields)
                + ". Name them so in the token format, or change the ${data...} names in the mapping.");
        m.endpoint = endpoint.toString();
        m.call = pretty(call, 0);
        return m;
    }

    // --- reading the definition ------------------------------------------------------------------------------------

    private String server() {
        Object servers = spec.get("servers");
        if (servers instanceof JSONArray && !((JSONArray) servers).isEmpty() && ((JSONArray) servers).get(0) instanceof JSONObject) {
            String url = text(((JSONObject) ((JSONArray) servers).get(0)).get("url"));
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        if (spec.get("host") != null) {   // Swagger 2
            Object schemes = spec.get("schemes");
            String scheme = schemes instanceof JSONArray && ((JSONArray) schemes).contains("https") ? "https"
                    : schemes instanceof JSONArray && !((JSONArray) schemes).isEmpty() ? String.valueOf(((JSONArray) schemes).get(0)) : "https";
            String base = text(spec.get("basePath"));
            return scheme + "://" + spec.get("host") + (base.equals("/") ? "" : base);
        }
        return "";
    }

    private List<JSONObject> securitySchemes(Operation operation) {
        List<JSONObject> out = new ArrayList<>();
        Object required = operation.definition.get("security") != null ? operation.definition.get("security") : spec.get("security");
        Object components = spec.get("components");
        Object schemes = components instanceof JSONObject ? ((JSONObject) components).get("securitySchemes") : spec.get("securityDefinitions");
        if (!(required instanceof JSONArray) || !(schemes instanceof JSONObject)) return out;
        for (Object requirement : (JSONArray) required) {
            if (!(requirement instanceof JSONObject)) continue;
            for (Object name : ((JSONObject) requirement).keySet()) {
                Object scheme = resolve(((JSONObject) schemes).get(name));
                if (scheme instanceof JSONObject) {
                    JSONObject s = (JSONObject) scheme;
                    if ("basic".equals(s.get("type"))) { s = new JSONObject(s); put(s, "type", "http"); put(s, "scheme", "basic"); }   // Swagger 2
                    out.add(s);
                }
            }
        }
        return out;
    }

    private List<JSONObject> parameters(Operation operation) {
        List<JSONObject> out = new ArrayList<>();
        for (Object source : Arrays.asList(operation.pathItem.get("parameters"), operation.definition.get("parameters")))
            if (source instanceof JSONArray) for (Object p : (JSONArray) source) {
                Object parameter = resolve(p);
                if (parameter instanceof JSONObject) out.add((JSONObject) parameter);
            }
        return out;
    }

    private JSONObject requestSchema(Operation operation) {
        JSONObject schema = schemaOf(resolve(operation.definition.get("requestBody")));
        if (schema != null) return schema;
        for (JSONObject parameter : parameters(operation))   // Swagger 2: a parameter "in": "body"
            if ("body".equals(parameter.get("in")) && resolve(parameter.get("schema")) instanceof JSONObject) return (JSONObject) resolve(parameter.get("schema"));
        return null;
    }

    /** The schema of the first successful (2xx) answer. */
    private JSONObject answerSchema(Operation operation) {
        Object responses = operation.definition.get("responses");
        if (!(responses instanceof JSONObject)) return null;
        List<String> codes = new ArrayList<>();
        for (Object code : ((JSONObject) responses).keySet()) codes.add(code.toString());
        java.util.Collections.sort(codes);
        for (String code : codes) if (code.startsWith("2")) {
            JSONObject schema = schemaOf(resolve(((JSONObject) responses).get(code)));
            if (schema != null) return schema;
        }
        return null;
    }

    /** The JSON schema of a request body or response: OpenAPI 3's content, or Swagger 2's schema. */
    private JSONObject schemaOf(Object holder) {
        if (!(holder instanceof JSONObject)) return null;
        Object content = ((JSONObject) holder).get("content");
        Object schema = ((JSONObject) holder).get("schema");
        if (content instanceof JSONObject) {
            Object media = ((JSONObject) content).get("application/json");
            if (media == null) for (Object type : ((JSONObject) content).keySet()) if (type.toString().contains("json")) media = ((JSONObject) content).get(type);
            if (media instanceof JSONObject) schema = ((JSONObject) media).get("schema");
        }
        Object resolved = resolve(schema);
        return resolved instanceof JSONObject ? (JSONObject) resolved : null;
    }

    /** A template for a request schema: required fields first, the others marked optional (${...?}); nesting followed two levels. */
    private Map<String, Object> template(JSONObject schema, String from, Mapping m, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object properties = schema.get("properties");
        if (!(properties instanceof JSONObject)) return out;
        List<String> required = new ArrayList<>();
        if (schema.get("required") instanceof JSONArray) for (Object r : (JSONArray) schema.get("required")) required.add(r.toString());
        List<String> names = new ArrayList<>(required);
        for (Object name : ((JSONObject) properties).keySet()) if (!names.contains(name.toString())) names.add(name.toString());
        for (String name : names) {
            if (!((JSONObject) properties).containsKey(name)) continue;
            Object property = resolve(((JSONObject) properties).get(name));
            if (property instanceof JSONObject && Boolean.TRUE.equals(((JSONObject) property).get("readOnly"))) continue;
            if (property instanceof JSONObject && ((JSONObject) property).get("properties") instanceof JSONObject && depth < 2) {
                Map<String, Object> nested = template((JSONObject) property, from + "." + name, m, depth + 1);
                if (!nested.isEmpty()) { out.put(name, nested); continue; }
            }
            boolean needed = required.contains(name);
            out.put(name, "${" + from + "." + name + (needed ? "" : "?") + "}");
            if (needed) field(m, (from + "." + name).substring("data.".length()));
            else if (!m.optionalFields.contains((from + "." + name).substring("data.".length()))) m.optionalFields.add((from + "." + name).substring("data.".length()));
        }
        return out;
    }

    /** Follows a local reference ({"$ref": "#/components/schemas/Payment"}); anything else is returned as it is. */
    private Object resolve(Object value) {
        for (int hops = 0; hops < 20 && value instanceof JSONObject && ((JSONObject) value).get("$ref") instanceof String; hops++) {
            String ref = (String) ((JSONObject) value).get("$ref");
            if (!ref.startsWith("#/")) return value;
            Object target = spec;
            for (String part : ref.substring(2).split("/")) {
                String key = part.replace("~1", "/").replace("~0", "~");
                target = target instanceof JSONObject ? ((JSONObject) target).get(key) : null;
            }
            if (target == null) return value;
            value = target;
        }
        return value;
    }

    private static void field(Mapping m, String name) { if (!m.dataFields.contains(name)) m.dataFields.add(name); }
    private static void query(StringBuilder endpoint, String name, String value) {
        endpoint.append(endpoint.indexOf("?") < 0 ? '?' : '&').append(name).append('=').append(value);
    }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
    @SuppressWarnings("unchecked") private static void put(JSONObject o, String key, Object value) { o.put(key, value); }

    /** JSON with two-space indents, short objects of plain values on one line, keys in the order given. */
    @SuppressWarnings("unchecked")
    static String pretty(Object value, int indent) {
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            boolean flat = true;
            for (Object v : map.values()) flat &= !(v instanceof Map);
            StringBuilder out = new StringBuilder("{");
            String pad = "  ".repeat(indent + 1);
            int i = 0;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                boolean oneLine = flat && indent > 0;
                out.append(i++ == 0 ? "" : ",").append(oneLine ? (i == 1 ? "" : " ") : "\n" + pad)
                   .append('"').append(JSONObject.escape(e.getKey())).append("\": ").append(pretty(e.getValue(), indent + 1));
            }
            return out.append(flat && indent > 0 || map.isEmpty() ? "}" : "\n" + "  ".repeat(indent) + "}").toString();
        }
        if (value instanceof String) return "\"" + JSONObject.escape((String) value) + "\"";
        return String.valueOf(value);
    }
}
