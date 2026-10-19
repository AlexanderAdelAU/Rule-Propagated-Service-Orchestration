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
 */
public final class RestAdapter {
    public static final String NAME = "rest";
    public static final int DEFAULT_TIMEOUT_MS = 10_000;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS)).build();

    private RestAdapter() { }

    /** True for an http or https URL with a host. */
    public static boolean validEndpoint(String endpoint) {
        try {
            URI uri = new URI(endpoint);
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request(service, operation, sequenceId, version, names, values),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("REST service " + service + "." + operation + " at " + endpoint
                    + " could not be reached: " + e, e);
        }
        if (response.statusCode() / 100 != 2)
            throw new IllegalStateException("REST service " + service + "." + operation + " at " + endpoint
                    + " answered HTTP " + response.statusCode() + ": " + response.body());
        Object result;
        try { result = new JSONParser().parse(response.body()); }
        catch (Exception e) { throw new IllegalStateException("REST service " + service + "." + operation + " did not return JSON: " + response.body()); }
        if (!(result instanceof JSONObject) || !((JSONObject)result).containsKey(returnAttribute))
            throw new IllegalStateException("REST service " + service + "." + operation + " must return {\"" + returnAttribute
                    + "\": {...}}, but returned: " + response.body());
        return ((JSONObject)result).toJSONString();
    }
}
