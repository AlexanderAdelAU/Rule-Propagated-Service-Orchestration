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
public class P1_Place extends BaseStochasticPetriNetPlace {
    
    private static final String PLACE_IDENTIFIER = "P1";
    
    /**
     * Standard constructor - creates place with default capacity (1) and no delay
     * 
     * @param sequenceID Token identifier for tracking
     */
    public P1_Place(String sequenceID) {
        super(sequenceID, PLACE_IDENTIFIER);
    }
    
    /**
     * Constructor with SPN parameters
     * 
     * @param sequenceID Token identifier for tracking
     * @param capacity Maximum tokens this place can hold (M(P) ≤ capacity)
     * @param processingDelayMs Deterministic processing delay in milliseconds
     */
    public P1_Place(String sequenceID, int capacity, long processingDelayMs) {
        super(sequenceID, PLACE_IDENTIFIER, capacity, processingDelayMs);
    }

    /**
     * FinancialSystem role: application validation.
     * Input: token JSON containing the application data object.
     * Output: validationResults JSON business object.
     */
    @Override
    @SuppressWarnings("unchecked")
    public String processToken(String token) {
        GuardMode previousMode = getGuardMode();
        try {
            setGuardMode(GuardMode.ALWAYS_TRUE);
            super.processToken(token);
        } finally {
            setGuardMode(previousMode);
        }

        String applicationId = textValue(token, "application_id", "APP-UNKNOWN");
        double annualIncome = numberValue(token, "annual_income", 0.0);
        double requestedAmount = numberValue(token, "requested_amount", 0.0);
        double creditScore = numberValue(token, "credit_score", 0.0);
        String fraudRisk = textValue(token, "fraud_risk", "unknown");

        boolean valid = !"APP-UNKNOWN".equals(applicationId) &&
                        annualIncome > 0.0 && requestedAmount > 0.0;
        String route = valid ? "valid" : "invalid";

        JSONObject routing = new JSONObject();
        routing.put("routing_path", route);

        JSONObject result = new JSONObject();
        result.put("application_id", applicationId);
        result.put("annual_income", annualIncome);
        result.put("requested_amount", requestedAmount);
        result.put("credit_score", creditScore);
        result.put("fraud_risk", fraudRisk);
        result.put("validation_status", route);
        result.put("routing_decision", routing);

        JSONObject response = new JSONObject();
        response.put("validationResults", result);
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