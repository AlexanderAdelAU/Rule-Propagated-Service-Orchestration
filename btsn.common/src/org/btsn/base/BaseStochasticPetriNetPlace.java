package org.btsn.base;

import java.util.Random;

import org.apache.log4j.Logger;
import org.btsn.base.TokenInfo;
import org.btsn.logger.PetriNetEventLogger;
import org.btsn.json.JsonResponseBuilder;
import org.btsn.json.JsonTokenParser;

/**
 * BasePetriNetPlace - Stochastic Petri Net (SPN) Place Implementation
 * 
 * A generic, reusable Place (P1, P2, P3, ..., Pn) for building Petri Net simulations.
 * 
 * STOCHASTIC PETRI NET SEMANTICS
 * ===============================
 * This implementation follows formal SPN theory:
 * 
 * Place Capacity: capacity(P) - maximum tokens this place can hold
 * Marking: M(P) - current number of tokens in place
 * Invariant: M(P) ≤ capacity(P) at all times
 * 
 * Token Processing:
 * 1. Token arrives at place P
 * 2. Check capacity: M(P) < capacity(P)?
 * 3. Accept token: M(P) := M(P) + 1
 * 4. Validate token (time window, structure)
 * 5. Hold token (stochastic delay from distribution)
 * 6. Evaluate guard (probabilistic or deterministic)
 * 7. Release token: M(P) := M(P) - 1
 * 8. Return token with routing decision
 * 
 * DELAY DISTRIBUTIONS
 * ===================
 * - DETERMINISTIC: Fixed delay (e.g., 100ms)
 * - EXPONENTIAL: Memoryless, rate-based (common for queueing)
 * - UNIFORM: Random between min and max
 * - NORMAL: Gaussian distribution (mean, stddev)
 * 
 * GUARD EVALUATION
 * ================
 * Guards control transition enabling (true/false decisions):
 * - ALWAYS_TRUE: Guard always passes
 * - ALWAYS_FALSE: Guard always fails
 * - RANDOM: Probabilistic (configurable probability)
 * - CUSTOM: Override for application-specific logic
 * 
 * TOKEN STRUCTURE
 * ===============
 * All methods use generic token in/token out:
 * - Input: token (JSON)
 * - Output: token (JSON with routing_decision)
 * - routing_decision.routing_path = "true" or "false"
 * - ServiceThread uses routing_path for XOR routing
 * 
 * CANONICAL BINDING
 * =================
 * One binding works for all methods:
 *   canonicalBinding(operation, token, token)
 * 
 * @author BTSN PetriNet Team
 */
public abstract class BaseStochasticPetriNetPlace {
    
    // ========== Delay Distribution Types ==========
    public enum DelayDistribution {
        DETERMINISTIC,  // Fixed delay
        EXPONENTIAL,    // Rate-based, memoryless
        UNIFORM,        // Random between min/max
        NORMAL          // Gaussian (mean, stddev)
    }
    
    // ========== Guard Evaluation Modes ==========
    public enum GuardMode {
        ALWAYS_TRUE,   // Guard always passes
        ALWAYS_FALSE,  // Guard always fails
        RANDOM,        // Probabilistic
        CUSTOM         // Override evaluateGuard()
    }
    
    protected final PetriNetEventLogger pnLogger;
    private static final Logger logger = Logger.getLogger(BaseStochasticPetriNetPlace.class);
    
    // Petri Net state
    protected final int placeCapacity;
    protected int currentMarking;  // M(P)
    protected final String placeIdentifier;  // P1, P2, P3, ...
    protected final String sequenceID;
    
    // Stochastic parameters
    protected DelayDistribution delayDistribution = DelayDistribution.DETERMINISTIC;
    protected long deterministicDelayMs = 0;
    protected double exponentialRate = 1.0;  // λ (events per second)
    protected long uniformMin = 0;
    protected long uniformMax = 100;
    protected double normalMean = 50.0;
    protected double normalStdDev = 10.0;
    
    // Guard evaluation
    protected GuardMode guardMode = GuardMode.RANDOM;
    protected double guardProbability = 0.5;
    
    protected final Random random = new Random();
    protected JsonResponseBuilder responseBuilder;
    protected JsonTokenParser tokenParser;
    
    // ========== Constructors ==========
    
    /**
     * Full constructor
     * 
     * @param sequenceID Token identifier
     * @param placeIdentifier Place ID (P1, P2, etc.)
     * @param capacity Maximum tokens
     * @param delayDistribution Type of delay distribution
     * @param delayParam Primary delay parameter (ms for deterministic, rate for exponential, etc.)
     */
    public BaseStochasticPetriNetPlace(String sequenceID, String placeIdentifier, 
                             int capacity, DelayDistribution delayDistribution, 
                             double delayParam) {
        this.sequenceID = sequenceID;
        this.placeIdentifier = placeIdentifier;
        this.placeCapacity = capacity;
        this.currentMarking = 0;
        this.delayDistribution = delayDistribution;
        
        // Configure delay based on distribution type
        switch (delayDistribution) {
            case DETERMINISTIC:
                this.deterministicDelayMs = (long) delayParam;
                break;
            case EXPONENTIAL:
                this.exponentialRate = delayParam;
                break;
            case UNIFORM:
                this.uniformMax = (long) delayParam;
                break;
            case NORMAL:
                this.normalMean = delayParam;
                break;
        }
        
        this.pnLogger = PetriNetEventLogger.getInstance();
        this.responseBuilder = new JsonResponseBuilder();
        this.tokenParser = new JsonTokenParser(placeIdentifier);
        
        pnLogger.logPlaceCreated(placeIdentifier, sequenceID, placeIdentifier, capacity);
    }
    
    /**
     * Simple constructor with deterministic delay
     */
    public BaseStochasticPetriNetPlace(String sequenceID, String placeIdentifier, 
                             int capacity, long delayMs) {
        this(sequenceID, placeIdentifier, capacity, DelayDistribution.DETERMINISTIC, delayMs);
    }
    
    /**
     * Default constructor: capacity=1, no delay
     */
    public BaseStochasticPetriNetPlace(String sequenceID, String placeIdentifier) {
        this(sequenceID, placeIdentifier, 1, 0);
    }
    
    // ==========================================================================
    // CORE TOKEN PROCESSING
    // ==========================================================================
    
    /**
     * Process token through this place
     * 
     * Standard SPN semantics:
     * 1. Check capacity
     * 2. Accept token (M(P) := M(P) + 1)
     * 3. Validate token
     * 4. Hold token (stochastic delay)
     * 5. Evaluate guard
     * 6. Release token (M(P) := M(P) - 1)
     * 7. Return enriched token
     * 
     * @param token Incoming token (JSON)
     * @return Token with routing_decision
     */
    public String processToken(String token) {
        long executionStart = System.currentTimeMillis();
        
        try {
            // Parse incoming token
            TokenInfo tokenInfo = tokenParser.parseIncomingToken(token);
            
            // Log token arrival
            pnLogger.logTokenArrival(placeIdentifier, sequenceID, tokenInfo);
            
            // Capacity check: M(P) < capacity(P)?
            if (!checkCapacity()) {
                String capacityError = String.format(
                    "Place %s at capacity: M(P)=%d, capacity(P)=%d",
                    placeIdentifier, currentMarking, placeCapacity
                );
                pnLogger.logCapacityViolation(placeIdentifier, sequenceID, 
                                             currentMarking, placeCapacity);
                return responseBuilder.createErrorResponse(
                    placeIdentifier, capacityError, sequenceID, placeIdentifier);
            }
            
            // Accept token: M(P) := M(P) + 1
            acceptToken(tokenInfo);
            
            // Small acceptance delay
            Thread.sleep(100);
            
            // Validate token
            if (!validateToken(tokenInfo)) {
                releaseToken(tokenInfo);
                String validationError = String.format(
                    "Token validation failed for id=%s at place=%s",
                    tokenInfo.getTokenId(), placeIdentifier
                );
                return responseBuilder.createErrorResponse(
                    placeIdentifier, validationError, sequenceID, placeIdentifier);
            }
            
            // Hold token in place (stochastic delay)
            long holdTime = sampleDelay();
            if (holdTime > 0) {
                Thread.sleep(holdTime);
            }
            
            // Evaluate guard condition
            boolean guardResult = evaluateGuard(tokenInfo);
            String routingPath = guardResult ? "true" : "false";
            
            long executionEnd = System.currentTimeMillis();
            long executionTime = executionEnd - executionStart;
            
            // Log execution
            pnLogger.logPlaceExecution(placeIdentifier, sequenceID, executionTime, "COMPLETED");
            
            logger.info(String.format(
                "PN_PROCESS: place=%s, seq=%s, guard=%s, delay=%dms, total=%dms",
                placeIdentifier, sequenceID, routingPath, holdTime, executionTime));
            
            // Create annotation
            String annotation = createPlaceAnnotation(executionStart, executionEnd, 
                                                     holdTime, routingPath);
            
            // Build response
            // NOTE: Do NOT set sequenceId here - it is infrastructure data owned by XML payload
            // Business services must not modify sequenceId; infrastructure will preserve it
            String jsonResponse = responseBuilder
                .setPlaceId("token")  // Generic output attribute
                .setMarking(currentMarking)
                .setExecutionTime(executionTime)
                .setAnnotation(annotation)
                .setStatus("COMPLETED")
                .setTokenInfo(tokenInfo)
                .setRoutingDecision(routingPath, guardResult, guardProbability)
                .addDataValue("place", placeIdentifier)
                .addDataValue("delayMs", String.valueOf(holdTime))  // Convert to String 
                .addDataValue("distribution", delayDistribution.toString())
                .build();
            
            // Release token: M(P) := M(P) - 1
            releaseToken(tokenInfo);
            
            // Log departure
            pnLogger.logTokenDeparture(placeIdentifier, sequenceID, tokenInfo, annotation);
            
            return jsonResponse;
            
        } catch (Exception e) {
            pnLogger.logPlaceError(placeIdentifier, sequenceID, e);
            
            if (currentMarking > 0) {
                currentMarking--;
                pnLogger.logMarkingChange(placeIdentifier, sequenceID, 
                                         currentMarking, "error_release");
            }
            
            return responseBuilder.createErrorResponse(
                placeIdentifier, e.getMessage(), sequenceID, placeIdentifier);
        }
    }
    
    // ==========================================================================
    // STOCHASTIC DELAY SAMPLING
    // ==========================================================================
    
    /**
     * Sample a delay from the configured distribution
     * 
     * @return Delay in milliseconds
     */
    protected long sampleDelay() {
        switch (delayDistribution) {
            case DETERMINISTIC:
                return deterministicDelayMs;
                
            case EXPONENTIAL:
                // Exponential: time = -ln(U) / λ
                // U is uniform random [0,1], λ is rate
                double u = random.nextDouble();
                double timeSeconds = -Math.log(u) / exponentialRate;
                return (long) (timeSeconds * 1000);
                
            case UNIFORM:
                // Uniform random between min and max
                long range = uniformMax - uniformMin;
                return uniformMin + (long) (random.nextDouble() * range);
                
            case NORMAL:
                // Normal (Gaussian) distribution
                double value = random.nextGaussian() * normalStdDev + normalMean;
                return Math.max(0, (long) value);  // Clamp to non-negative
                
            default:
                return 0;
        }
    }
    
    // ==========================================================================
    // GUARD EVALUATION
    // ==========================================================================
    
    /**
     * Evaluate guard condition for transition enabling
     * 
     * @param tokenInfo Token being evaluated
     * @return true if guard passes, false otherwise
     */
    protected boolean evaluateGuard(TokenInfo tokenInfo) {
        switch (guardMode) {
            case ALWAYS_TRUE:
                return true;
                
            case ALWAYS_FALSE:
                return false;
                
            case RANDOM:
                return random.nextDouble() < guardProbability;
                
            case CUSTOM:
                return evaluateCustomGuard(tokenInfo);
                
            default:
                return true;
        }
    }
    
    /**
     * Custom guard evaluation - override in subclasses
     * 
     * Examples:
     * - Token data field checks
     * - Time-based conditions
     * - Resource availability
     * - Business rules
     * 
     * @param tokenInfo Token being evaluated
     * @return true if guard passes
     */
    protected boolean evaluateCustomGuard(TokenInfo tokenInfo) {
        // Default: always true
        // Subclasses override for custom logic
        return true;
    }
    
    // ==========================================================================
    // DUAL-INPUT PROCESSING (JOIN)
    // ==========================================================================
    
    /**
     * Process two synchronized inputs from a JOIN node
     * 
     * FIX: Now properly evaluates stochastic guard after join completion
     * instead of always returning true with probability 1.0
     * 
     * @param inputData1 First token
     * @param inputData2 Second token
     * @return Merged token with stochastic routing decision
     */
    public String processToken(String inputData1, String inputData2) {
        long executionStart = System.currentTimeMillis();
        
        logger.info(String.format(
            "PN_JOIN_PROCESS: place=%s, seq=%s - Processing synchronized inputs",
            placeIdentifier, sequenceID));
        
        try {
            TokenInfo tokenInfo1 = tokenParser.parseIncomingToken(inputData1);
            TokenInfo tokenInfo2 = tokenParser.parseIncomingToken(inputData2);
            
            logger.info(String.format(
                "PN_JOIN_PROCESS: place=%s - Input1=%s, Input2=%s",
                placeIdentifier, 
                tokenInfo1 != null ? tokenInfo1.getTokenId() : "null",
                tokenInfo2 != null ? tokenInfo2.getTokenId() : "null"));
            
            pnLogger.logTokenArrival(placeIdentifier, sequenceID, tokenInfo1);
            
            if (!checkCapacity()) {
                return responseBuilder.createErrorResponse(
                    placeIdentifier, "Place at capacity", sequenceID, placeIdentifier);
            }
            
            acceptToken(tokenInfo1);
            
            if (!validateToken(tokenInfo1)) {
                releaseToken(tokenInfo1);
                return responseBuilder.createErrorResponse(
                    placeIdentifier, "Token validation failed", sequenceID, placeIdentifier);
            }
            
            // Hold token with stochastic delay
            long holdTime = sampleDelay();
            if (holdTime > 0) {
                Thread.sleep(holdTime);
            }
            
            // =================================================================
            // FIX: Evaluate stochastic guard AFTER join completes
            // Previously was hardcoded: guardResult=true, probability=1.0
            // Now properly calls evaluateGuard() for XOR output routing
            // =================================================================
            boolean guardResult = evaluateGuard(tokenInfo1);
            String routingPath = guardResult ? "true" : "false";
            
            long executionEnd = System.currentTimeMillis();
            long executionTime = executionEnd - executionStart;
            
            pnLogger.logPlaceExecution(placeIdentifier, sequenceID, executionTime, "JOIN_COMPLETED");
            
            logger.info(String.format(
                "PN_JOIN_PROCESS: place=%s, seq=%s, guard=%s (prob=%.2f), delay=%dms, total=%dms",
                placeIdentifier, sequenceID, routingPath, guardProbability, holdTime, executionTime));
            
            String annotation = createJoinAnnotation(executionStart, executionEnd, 
                                                     tokenInfo1, tokenInfo2, holdTime);
            
            // Build response
            // NOTE: Do NOT set sequenceId here - it is infrastructure data owned by XML payload
            // Business services must not modify sequenceId; infrastructure will preserve it
            String jsonResponse = responseBuilder
                .setPlaceId("token")
                .setMarking(currentMarking)
                .setExecutionTime(executionTime)
                .setAnnotation(annotation)
                .setStatus("JOIN_COMPLETED")
                .setTokenInfo(tokenInfo1)
                // FIX: Use evaluated guard result and configured probability
                // Was: .setRoutingDecision("true", true, 1.0)
                .setRoutingDecision(routingPath, guardResult, guardProbability)
                .build();
            
            releaseToken(tokenInfo1);
            pnLogger.logTokenDeparture(placeIdentifier, sequenceID, tokenInfo1, annotation);
            
            return jsonResponse;
            
        } catch (Exception e) {
            pnLogger.logPlaceError(placeIdentifier, sequenceID, e);
            
            if (currentMarking > 0) {
                currentMarking--;
            }
            
            return responseBuilder.createErrorResponse(
                placeIdentifier, e.getMessage(), sequenceID, placeIdentifier);
        }
    }
    
    // ==========================================================================
    // HELPER METHODS
    // ==========================================================================
    
    protected boolean checkCapacity() {
        boolean hasCapacity = currentMarking < placeCapacity;
        pnLogger.logCapacityCheck(placeIdentifier, sequenceID, 
                                 currentMarking, placeCapacity, hasCapacity);
        return hasCapacity;
    }
    
    protected void acceptToken(TokenInfo tokenInfo) {
        currentMarking++;
        pnLogger.logMarkingChange(placeIdentifier, sequenceID, 
                                 currentMarking, "token_accepted");
        pnLogger.logTokenHeld(placeIdentifier, sequenceID, tokenInfo, 
                             currentMarking, placeCapacity);
    }
    
    protected void releaseToken(TokenInfo tokenInfo) {
        currentMarking--;
        pnLogger.logMarkingChange(placeIdentifier, sequenceID, 
                                 currentMarking, "token_released");
        pnLogger.logTokenReleased(placeIdentifier, sequenceID, tokenInfo, currentMarking);
    }
    
    protected boolean validateToken(TokenInfo tokenInfo) {
        if (tokenInfo == null) {
            return false;
        }
        
        // Time window validation
        if (tokenInfo.getNotAfter() > 0) {
            long currentTime = System.currentTimeMillis();
            long notAfter = tokenInfo.getNotAfter();
            boolean timeValid = currentTime < notAfter;
            
            pnLogger.logValidityWindowCheck(placeIdentifier, sequenceID,
                                           currentTime, notAfter, timeValid);
            
            if (!timeValid) {
                return false;
            }
        }
        
        return true;
    }
    
    protected String createPlaceAnnotation(long entryTime, long exitTime, 
                                           long delayMs, String routingPath) {
        return String.format(
            "{\"place\":\"%s\",\"entryTime\":%d,\"exitTime\":%d," +
            "\"residenceTime\":%d,\"delayMs\":%d,\"marking\":%d,\"capacity\":%d," +
            "\"distribution\":\"%s\",\"routingPath\":\"%s\"}",
            placeIdentifier,
            entryTime,
            exitTime,
            exitTime - entryTime,
            delayMs,
            currentMarking,
            placeCapacity,
            delayDistribution.toString(),
            routingPath
        );
    }
    
    protected String createJoinAnnotation(long entryTime, long exitTime, 
                                          TokenInfo token1, TokenInfo token2,
                                          long delayMs) {
        return String.format(
            "{\"place\":\"%s\",\"entryTime\":%d,\"exitTime\":%d,\"residenceTime\":%d," +
            "\"delayMs\":%d,\"marking\":%d,\"capacity\":%d,\"joinType\":\"AND_JOIN\"," +
            "\"input1Token\":\"%s\",\"input2Token\":\"%s\"}",
            placeIdentifier,
            entryTime,
            exitTime,
            exitTime - entryTime,
            delayMs,
            currentMarking,
            placeCapacity,
            token1 != null ? token1.getTokenId() : "null",
            token2 != null ? token2.getTokenId() : "null"
        );
    }
    
    // ==========================================================================
    // CONFIGURATION METHODS
    // ==========================================================================
    
    /**
     * Configure exponential delay distribution
     * 
     * @param rate Events per second (λ)
     */
    public void setExponentialDelay(double rate) {
        this.delayDistribution = DelayDistribution.EXPONENTIAL;
        this.exponentialRate = rate;
    }
    
    /**
     * Configure uniform delay distribution
     * 
     * @param minMs Minimum delay
     * @param maxMs Maximum delay
     */
    public void setUniformDelay(long minMs, long maxMs) {
        this.delayDistribution = DelayDistribution.UNIFORM;
        this.uniformMin = minMs;
        this.uniformMax = maxMs;
    }
    
    /**
     * Configure normal (Gaussian) delay distribution
     * 
     * @param meanMs Mean delay
     * @param stdDevMs Standard deviation
     */
    public void setNormalDelay(double meanMs, double stdDevMs) {
        this.delayDistribution = DelayDistribution.NORMAL;
        this.normalMean = meanMs;
        this.normalStdDev = stdDevMs;
    }
    
    /**
     * Configure deterministic (fixed) delay
     * 
     * @param delayMs Fixed delay in milliseconds
     */
    public void setDeterministicDelay(long delayMs) {
        this.delayDistribution = DelayDistribution.DETERMINISTIC;
        this.deterministicDelayMs = delayMs;
    }
    
    /**
     * Set guard mode
     */
    public void setGuardMode(GuardMode mode) {
        this.guardMode = mode;
    }
    
    /**
     * Set guard probability (for RANDOM mode)
     */
    public void setGuardProbability(double probability) {
        this.guardProbability = Math.max(0.0, Math.min(1.0, probability));
    }
    
    // ==========================================================================
    // STATE QUERIES
    // ==========================================================================
    
    public int getMarking() { return currentMarking; }
    public int getCapacity() { return placeCapacity; }
    public String getPlaceIdentifier() { return placeIdentifier; }
    public boolean isAtCapacity() { return currentMarking >= placeCapacity; }
    public boolean isEmpty() { return currentMarking == 0; }
    public double getUtilization() { return (double) currentMarking / placeCapacity * 100.0; }
    protected String getSequenceID() { return sequenceID; }
    public DelayDistribution getDelayDistribution() { return delayDistribution; }
    public GuardMode getGuardMode() { return guardMode; }
    public double getGuardProbability() { return guardProbability; }
}