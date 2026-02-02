package org.btsn.places;

import org.btsn.base.BaseStochasticPetriNetPlace;

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
}