package org.btsn.places;

import org.btsn.base.BaseStochasticPetriNetPlace;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * P1_Place - Stochastic Petri Net Place
 * 
 * A reusable SPN Place that processes tokens with configurable:
 * - Stochastic delay distributions (Exponential, Uniform, Normal, Deterministic)
 * - Guard evaluation modes (Always True, Always False, Random, Custom)
 * - Place capacity constraints
 * 
 * All SPN functionality is inherited from BaseSPNPlace.
 * 
 * SPN SEMANTICS
 * =============
 * Each token processed follows formal SPN semantics:
 * 
 *   1. Capacity Check  → M(P) < capacity(P)?
 *   2. Accept Token    → M(P) := M(P) + 1
 *   3. Validate Token  → Time window, structure checks
 *   4. Hold Token      → Sample from delay distribution
 *   5. Evaluate Guard  → Probabilistic or deterministic
 *   6. Release Token   → M(P) := M(P) - 1
 *   7. Route Token     → routing_decision: "true" or "false"
 * 
 * DELAY DISTRIBUTIONS
 * ===================
 * Configure via setters inherited from BaseSPNPlace:
 * 
 *   setDeterministicDelay(100)      → Fixed 100ms delay
 *   setExponentialDelay(2.0)        → λ=2.0 events/sec (memoryless)
 *   setUniformDelay(50, 150)        → Random between 50-150ms
 *   setNormalDelay(100, 20)         → Gaussian μ=100ms, σ=20ms
 * 
 * GUARD MODES
 * ===========
 * Configure via setGuardMode():
 * 
 *   ALWAYS_TRUE   → Transition always fires
 *   ALWAYS_FALSE  → Transition never fires (blocked)
 *   RANDOM        → Fires with probability p (default 0.5)
 *   CUSTOM        → Override evaluateCustomGuard() for business logic
 * 
 * EXAMPLE PROCESS DEFINITION
 * ==========================
 * 
 * As a simple passthrough place:
 * {
 *   "id": "P1",
 *   "service": "P1_Place",
 *   "operation": "processToken"
 * }
 * 
 * As a decision point (XOR split):
 * {
 *   "id": "Decision",
 *   "service": "P1_Place",
 *   "operation": "processToken",
 *   "guardMode": "RANDOM",
 *   "guardProbability": 0.7
 * }
 * 
 * CORE METHODS (inherited from BaseSPNPlace)
 * ==========================================
 * - processToken(token)              → Single token processing with SPN semantics
 * - processToken(token1, token2)     → JOIN: Synchronized dual-input processing
 * 
 * @see BaseStochasticPetriNetPlace for full SPN implementation details
 * @author BTSN PetriNet Team
 */
public class P5_Place extends BaseStochasticPetriNetPlace {
    
    private static final String PLACE_IDENTIFIER = "P5";
    
    /**
     * Standard constructor
     * 
     * @param sequenceID Token identifier
     */
    public P5_Place(String sequenceID) {
        super(sequenceID, PLACE_IDENTIFIER);
    }
    
    /**
     * Constructor with capacity and delay
     * 
     * @param sequenceID Token identifier
     * @param capacity Maximum tokens
     * @param processingDelayMs Processing delay in milliseconds
     */
    public P5_Place(String sequenceID, int capacity, long processingDelayMs) {
        super(sequenceID, PLACE_IDENTIFIER, capacity, processingDelayMs);
    }

    /**
     * FinancialSystem role: final lending decision.
     * Input: underwritingResults JSON business object.
     * Output: decisionResults JSON business object.
     */
    @Override
    @SuppressWarnings("unchecked")
    public String processToken(String underwritingResults) {
        GuardMode previousMode = getGuardMode();
        try {
            setGuardMode(GuardMode.ALWAYS_TRUE);
            super.processToken(underwritingResults);
        } finally {
            setGuardMode(previousMode);
        }

        String applicationId = textValue(underwritingResults, "application_id", "APP-UNKNOWN");
        String decision = textValue(underwritingResults, "underwriting_decision", "conditional");

        JSONObject result = new JSONObject();
        result.put("application_id", applicationId);
        result.put("final_decision", decision);
        result.put("status", "complete");

        JSONObject response = new JSONObject();
        response.put("decisionResults", result);
        return response.toJSONString();
    }

    private static JSONObject parseObject(String json) {
        if (json == null) return new JSONObject();
        try {
            Object parsed = new JSONParser().parse(json);
            return parsed instanceof JSONObject ? (JSONObject) parsed : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static Object findValue(JSONObject object, String key) {
        if (object == null) return null;
        if (object.containsKey(key)) return object.get(key);
        for (Object value : object.values()) {
            if (value instanceof JSONObject) {
                Object found = findValue((JSONObject) value, key);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String textValue(String json, String key, String defaultValue) {
        Object value = findValue(parseObject(json), key);
        return value == null ? defaultValue : value.toString();
    }

    private static double numberValue(String json, String key, double defaultValue) {
        String value = textValue(json, key, null);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}