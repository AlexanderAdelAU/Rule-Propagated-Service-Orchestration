package org.btsn.base;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * BaseBusinessPetriNetPlace
 *
 * Minimal base for business services mapped onto the Petri-net orchestration
 * infrastructure.
 *
 * This class deliberately contains NO stochastic simulation:
 * - no random guard
 * - no artificial processing delay
 * - no simulated place capacity
 * - no token routing or fork/join mechanics
 *
 * Those behaviours belong to the deployed orchestration infrastructure and to
 * the actual compute/network resources on which it runs. Subclasses implement
 * only the business operation assigned to the place.
 *
 * Canonical bindings name the business JSON objects consumed and produced by
 * each operation. The JSON objects themselves define the business data carried.
 */
public abstract class BaseBusinessPetriNetPlace {

    private final String sequenceID;
    private final String placeIdentifier;

    protected BaseBusinessPetriNetPlace(String sequenceID, String placeIdentifier) {
        this.sequenceID = sequenceID;
        this.placeIdentifier = placeIdentifier;
    }

    protected final String getSequenceID() {
        return sequenceID;
    }

    protected final String getPlaceIdentifier() {
        return placeIdentifier;
    }

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
