package org.btsn.business;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Neutral base for logical business-service implementations.
 *
 * This class contains only helpers for reading and producing business data.
 * It has no knowledge of Petri-net places, transitions, queues, schedulers,
 * channels, ports, deployment nodes or workflow topology.
 */
public abstract class BaseBusinessService {

    protected final JSONObject parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new JSONObject();
        }

        try {
            Object parsed = new JSONParser().parse(json);
            return parsed instanceof JSONObject ? (JSONObject) parsed : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    protected final Object findValue(JSONObject object, String key) {
        if (object == null || key == null) {
            return null;
        }

        if (object.containsKey(key)) {
            return object.get(key);
        }

        for (Object value : object.values()) {
            if (value instanceof JSONObject) {
                Object found = findValue((JSONObject) value, key);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    protected final String textValue(String json, String key, String defaultValue) {
        Object value = findValue(parseObject(json), key);
        return value == null ? defaultValue : value.toString();
    }

    protected final double numberValue(String json, String key, double defaultValue) {
        String value = textValue(json, key, null);
        if (value == null) {
            return defaultValue;
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    protected final JSONObject routingDecision(String path) {
        JSONObject routing = new JSONObject();
        routing.put("routing_path", path);
        return routing;
    }

    @SuppressWarnings("unchecked")
    protected final String businessResult(String attributeName, JSONObject result) {
        JSONObject response = new JSONObject();
        response.put(attributeName, result);
        return response.toJSONString();
    }
}
