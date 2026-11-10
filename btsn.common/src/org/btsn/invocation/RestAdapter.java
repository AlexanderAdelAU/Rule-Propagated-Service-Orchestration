package org.btsn.invocation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Invokes a service over HTTP instead of in-process: the placement's "endpoint" answers the same contract
 * as a Java service. The host POSTs
 *
 *   {"service": ..., "operation": ..., "sequenceId": ..., "version": ..., "inputs": {name: value, ...}}
 *
 * and expects the body a Java service would return: {returnAttribute: {..., "routing_decision": {"routing_path": ...}}}.
 * The service can be written in any language. Transitions, rules and tokens are unchanged; this never routes.
 *
 * A placement with a "call" mapping (CallMapping) calls an API that was not written for RPSO instead: its own
 * method, headers and body, with its answer turned into the result above.
 */
public final class RestAdapter {
    public static final String NAME = "rest";
    public static final int DEFAULT_TIMEOUT_MS = 10_000;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS)).build();

    private RestAdapter() { }

    /** True for an http or https URL with a host; ${...} values filled in for each call are allowed. */
    public static boolean validEndpoint(String endpoint) {
        try {
            URI uri = new URI(endpoint.replaceAll("\\$\\{[^}]*\\}", "x"));
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** The request body: the call's identity and its named inputs (JSON inputs as objects, others as text). */
    @SuppressWarnings("unchecked")
    static String request(String service, String operation, String sequenceId, String version,
                          List<String> names, List<String> values) {
        JSONObject inputs = new JSONObject();
        for (int i = 0; i < names.size(); i++) {
            String value = i < values.size() ? values.get(i) : null;
            Object parsed = value;
            try { if (value != null) parsed = new JSONParser().parse(value); }
            catch (Exception notJson) { parsed = value; }
            inputs.put(names.get(i), parsed);
        }
        JSONObject body = new JSONObject();
        body.put("service", service);
        body.put("operation", operation);
        body.put("sequenceId", sequenceId == null ? "" : sequenceId);
        body.put("version", version == null ? "" : version);
        body.put("inputs", inputs);
        return body.toJSONString();
    }

    /** Calls the endpoint and returns its result, checked to carry the contract's return attribute. */
    public static String invoke(String endpoint, int timeoutMs, String service, String operation, String sequenceId,
                                String version, List<String> names, List<String> values, String returnAttribute)
            throws Exception {
        return invoke(endpoint, timeoutMs, null, service, operation, sequenceId, version, names, values, returnAttribute);
    }

    /** As above; with a call mapping, the endpoint is called as its own API describes and its answer mapped. */
    public static String invoke(String endpoint, int timeoutMs, CallMapping call, String service, String operation,
                                String sequenceId, String version, List<String> names, List<String> values,
                                String returnAttribute) throws Exception {
        String rpso = request(service, operation, sequenceId, version, names, values);
        String where = "REST service " + service + "." + operation;
        HttpRequest.Builder builder;
        String address = endpoint;
        if (call == null) {
            builder = HttpRequest.newBuilder(URI.create(endpoint)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(rpso, StandardCharsets.UTF_8));
        } else {
            CallMapping.Values given = new CallMapping.Values(names, values, sequenceId, version);
            String body;
            try {
                address = call.url(endpoint, given);
                body = call.body(given, rpso);
                builder = HttpRequest.newBuilder(URI.create(address));
                if (body != null) builder.header("Content-Type", "application/json");
                builder.header("Accept", "application/json");
                for (java.util.Map.Entry<String, String> h : call.headers(given).entrySet()) builder.setHeader(h.getKey(), h.getValue());
            } catch (IllegalStateException e) {
                throw new IllegalStateException(where + " could not be called: " + e.getMessage(), e);
            }
            builder.method(call.method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        int waited = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        HttpRequest request = builder.timeout(Duration.ofMillis(waited)).build();
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            // Reached, but too slow: say so, rather than that it could not be reached.
            throw new IllegalStateException(where + " at " + address + " did not answer within " + waited + " ms (timeoutMs)", e);
        } catch (Exception e) {
            throw new IllegalStateException(where + " at " + address + " could not be reached: " + e, e);
        }
        if (call != null && !call.sendsRpsoRequest()) {
            if (!call.answers(response.statusCode()))
                throw new IllegalStateException(where + " at " + address + " answered HTTP " + response.statusCode() + ": " + response.body());
            try {
                return call.publish(response.statusCode(), response.body(), returnAttribute);
            } catch (IllegalStateException e) {
                throw new IllegalStateException(where + ": " + e.getMessage(), e);
            }
        }
        if (response.statusCode() / 100 != 2)
            throw new IllegalStateException(where + " at " + address + " answered HTTP " + response.statusCode() + ": " + response.body());
        Object result;
        try { result = new JSONParser().parse(response.body()); }
        catch (Exception e) { throw new IllegalStateException(where + " did not return JSON: " + response.body()); }
        if (!(result instanceof JSONObject) || !((JSONObject)result).containsKey(returnAttribute))
            throw new IllegalStateException(where + " must return {\"" + returnAttribute + "\": {...}}, but returned: " + response.body());
        return ((JSONObject)result).toJSONString();
    }

    /** One call made for its author to look at: what was sent, and what came of it. */
    public static final class Trial {
        /** The request as sent: method and address, headers (keys from the environment hidden), then the body. */
        public String request = "";
        /** The result the host would publish, or null when the call failed. */
        public String published;
        /** The route T_out would take, or null when the result names none. */
        public String route;
        /** Why the call failed, or null when it did not. */
        public String failure;
    }

    /** Makes one call as the host would and reports it, for the editor's Try a call. Never throws. */
    public static Trial trial(String endpoint, int timeoutMs, Object call, String service, String operation,
                              String sequenceId, String version, List<String> names, List<String> values,
                              String returnAttribute) {
        Trial trial = new Trial();
        try {
            CallMapping mapping = CallMapping.parse(call);
            String rpso = request(service, operation, sequenceId, version, names, values);
            StringBuilder sent = new StringBuilder();
            if (mapping == null) {
                sent.append("POST ").append(endpoint).append("\nContent-Type: application/json\n\n").append(rpso);
            } else {
                CallMapping.Values given = new CallMapping.Values(names, values, sequenceId, version);
                String body = mapping.body(given, rpso);
                sent.append(mapping.method).append(' ').append(mapping.url(endpoint, given)).append('\n');
                if (body != null) sent.append("Content-Type: application/json\n");
                java.util.Map<String, String> filled = mapping.headers(given);
                for (java.util.Map.Entry<String, String> h : mapping.headers.entrySet())
                    sent.append(h.getKey()).append(": ").append(h.getValue().contains("${env:")
                            ? h.getValue().replaceAll("\\$\\{env:[^}]*\\}", "(hidden)") : filled.get(h.getKey())).append('\n');
                if (body != null) sent.append('\n').append(body);
            }
            trial.request = sent.toString();
            trial.published = invoke(endpoint, timeoutMs, mapping, service, operation, sequenceId, version, names, values, returnAttribute);
            Object result = ((JSONObject) new JSONParser().parse(trial.published)).get(returnAttribute);
            Object decision = result instanceof JSONObject ? ((JSONObject) result).get("routing_decision") : null;
            Object path = decision instanceof JSONObject ? ((JSONObject) decision).get("routing_path") : null;
            trial.route = path == null ? null : path.toString();
        } catch (Exception e) {
            trial.failure = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return trial;
    }
}
