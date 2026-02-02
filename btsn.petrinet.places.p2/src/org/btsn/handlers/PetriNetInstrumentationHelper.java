package org.btsn.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;
import org.btsn.constants.VersionConstants;
import org.btsn.derby.Analysis.BuildServiceAnalysisDatabase;
import org.btsn.utils.XPathHelperCommon;

/**
 * PetriNetInstrumentationHelper - Orchestrates Petri Net analysis instrumentation
 * 
 * PURPOSE: Captures token movements through transitions for complete Petri Net analysis
 * 
 * DESIGN: Lightweight helper that ServiceThread calls at strategic points:
 *   1. When tokens enter a place (after T_in fires)
 *   2. When tokens exit a place (before T_out fires)
 *   3. When tokens arrive at join points
 * 
 * RESPONSIBILITIES:
 *   - Record transition firings (token movements)
 *   - Track token genealogy (parent-child relationships at forks)
 *   - Coordinate join synchronization (which tokens contribute to joins)
 *   - Enrich XML payloads with transition metadata
 *   - Track transition buffer sizes (queue depth at T_in)
 * 
 * INTEGRATION: Called by ServiceThread with ~4 method calls, zero bloat to ServiceThread
 * 
 * @author BTSN Petri Net Team
 * @version 1.1 - Added buffer size tracking
 */
public class PetriNetInstrumentationHelper {

    private static final Logger logger = Logger.getLogger(PetriNetInstrumentationHelper.class);
    
    /**
     * Returns false - this is the FULL version (petrinet)
     * The healthcare stub version returns true
     */
    public static final boolean IS_STUB = false;
    
    /**
     * Check if this is the stub version
     * @return true for stub (healthcare), false for full version (petrinet)
     */
    public static boolean isStub() {
        return IS_STUB;
    }
    
    // Database writer for all Petri Net data
    private final BuildServiceAnalysisDatabase dbWriter;
    
    // XML parser for extracting payload data
    private final XPathHelperCommon xph;
    
    // Service identity
    private final String serviceChannel;
    private final String servicePort;
    
    // In-memory join state tracking (keyed by "joinId_workflowBase")
    private final ConcurrentHashMap<String, JoinState> activeJoins;
    
    // =============================================================================
    // MONOTONIC TIMESTAMP TRACKING
    // =============================================================================
    // Tracks last recorded timestamp per token to ensure event ordering.
    // Prevents race conditions where BUFFERED could be recorded before EXIT
    // when sender and receiver threads call System.currentTimeMillis() independently.
    // =============================================================================
    private static final ConcurrentHashMap<Integer, Long> lastEventTimestamp = new ConcurrentHashMap<>();
    
    /**
     * Get a monotonically increasing timestamp for a token.
     * 
     * This ensures that each event for a token has a timestamp strictly greater
     * than all previous events for that token, preventing race conditions where
     * concurrent threads could record events out of order.
     * 
     * THREAD SAFETY: Uses ConcurrentHashMap.compute() for atomic read-modify-write.
     * 
     * @param tokenId The token's sequence ID
     * @return A timestamp guaranteed to be > any previous timestamp for this token
     */
    private long getMonotonicTimestamp(int tokenId) {
        long now = System.currentTimeMillis();
        return lastEventTimestamp.compute(tokenId, (id, lastTs) -> {
            if (lastTs == null) {
                return now;
            }
            // Ensure strictly increasing: at least 1ms after previous event
            return Math.max(now, lastTs + 1);
        });
    }
    
    /**
     * Clean up timestamp tracking for completed workflows.
     * Call this when a workflow terminates to prevent memory leaks.
     * 
     * @param workflowBase The workflow base ID (e.g., 1000000)
     */
    public void cleanupWorkflowTimestamps(int workflowBase) {
        // Remove all tokens belonging to this workflow (base + 0-99)
        for (int i = 0; i < 100; i++) {
            lastEventTimestamp.remove(workflowBase + i);
        }
        logger.debug("Cleaned up timestamp tracking for workflow " + workflowBase);
    }
    
    // Configuration flags
    private boolean recordTransitions = true;
    private boolean recordGenealogy = true;
    private boolean recordJoins = true;
    
    /**
     * Constructor - Initialize the Petri Net instrumentation system
     * 
     * @param serviceChannel The service channel (for logging/debugging)
     * @param servicePort The service port (for logging/debugging)
     */
    public PetriNetInstrumentationHelper(String serviceChannel, String servicePort) {
        this.serviceChannel = serviceChannel;
        this.servicePort = servicePort;
        this.dbWriter = new BuildServiceAnalysisDatabase();
        this.xph = new XPathHelperCommon();
        this.activeJoins = new ConcurrentHashMap<>();
        
        logger.info("=== PETRI NET INSTRUMENTATION INITIALIZED ===");
        logger.info("Service: " + serviceChannel + ":" + servicePort);
        logger.info("Recording: Transitions=" + recordTransitions + 
                   ", Genealogy=" + recordGenealogy + 
                   ", Joins=" + recordJoins);
    }
    
    // =============================================================================
    // ADMIN VERSION CHECK (v999 skip logic)
    // =============================================================================
    
    /**
     * Check if this token belongs to an admin/collection workflow (v999)
     * that should skip instrumentation recording.
     * 
     * v999 tokens (999000000-999999999) are used for initialization and 
     * data collection workflows that should not pollute analysis data.
     * 
     * @param tokenId The token's sequence ID
     * @return true if instrumentation should be skipped for this token
     */
    private boolean shouldSkipInstrumentation(int tokenId) {
        return VersionConstants.isAdminVersion(tokenId);
    }
    
    // =============================================================================
    // PRIMARY ENTRY POINTS (Called by ServiceThread)
    // =============================================================================
    
    /**
     * Record token entering a place (T_in transition fired)
     * 
     * Called from ServiceThread.run() after payload parsing, before service invocation
     * 
     * @param xmlPayload The incoming XML payload containing transition metadata
     * @param tokenId The token's sequence ID
     * @param placeName The place (service) the token is entering
     * @param nodeType The node type (EdgeNode, ForkNode, JoinNode, etc.)
     * @param workflowStartTime The workflow instance start time
     * @param bufferSize The transition buffer size (queue depth when token was dequeued)
     * @param eventGeneratorTimestamp The timestamp when token was generated (null if not from event generator)
     * @param sourceEventGenerator The identity of the event generator that created the token (e.g., "TRIAGE_EVENTGENERATOR")
     */
    public void recordTokenEntering(String xmlPayload, int tokenId, String placeName, 
                                   String nodeType, long workflowStartTime, int bufferSize,
                                   String eventGeneratorTimestamp, String sourceEventGenerator) {
        // Skip instrumentation for admin/collection workflows (v999)
        if (shouldSkipInstrumentation(tokenId)) {
            logger.debug("Skipping instrumentation for admin token " + tokenId + " (v999)");
            return;
        }
        
        try {
            logger.debug("Token " + tokenId + " entering place: " + placeName + " (buffer=" + bufferSize + ")");
            
            // Extract transition metadata from payload
            String previousPlace = extractPreviousPlace(xmlPayload);
            String enteringTransition = extractTransitionId(xmlPayload, buildTInTransitionId(placeName));
            String transitionType = extractTransitionType(xmlPayload, nodeType);
            
            // CHECK FOR EVENT GENERATOR TIMESTAMP
            // If present and non-zero, this is the first place - record GENERATED event
            // Zero means timestamp was already consumed by upstream service
            if (eventGeneratorTimestamp != null && !eventGeneratorTimestamp.isEmpty()) {
                long eventGenTimestamp = Long.parseLong(eventGeneratorTimestamp);
                
                // Only record GENERATED if timestamp is non-zero (zero = already processed)
                if (eventGenTimestamp > 0) {
                    
                    // Use the sourceEventGenerator passed from ServiceThread (extracted from monitorDataMap)
                    // Fall back to "EVENT_GENERATOR" only if not provided (backward compatibility)
                    String generatorId = (sourceEventGenerator != null && !sourceEventGenerator.isEmpty()) 
                        ? sourceEventGenerator : "EVENT_GENERATOR";
                    
                    logger.info("GENERATED: Token " + tokenId + " from " + generatorId + " at " + 
                               eventGenTimestamp + " -> " + placeName);
                    
                    // Record the GENERATED event with the original timestamp
                    if (recordTransitions) {
                        TreeMap<String, String> record = new TreeMap<>();
                        record.put("timestamp", Long.toString(eventGenTimestamp));
                        record.put("transitionId", generatorId);
                        record.put("transitionType", "EventGenerator");
                        record.put("tokenId", Integer.toString(tokenId));
                        record.put("workflowBase", Integer.toString(calculateWorkflowBase(tokenId)));
                        record.put("fromPlace", generatorId);
                        record.put("toPlace", placeName);
                        record.put("forkDecision", "");
                        record.put("joinState", "");
                        record.put("bufferSize", "0");
                        record.put("ruleVersion", VersionConstants.getVersionFromSequenceId(tokenId));
                        record.put("eventType", "GENERATED");
                        record.put("arcValue", "");
                        
                        dbWriter.writeTransitionFiring(record);
                    }
                }
            }
            
            // Record the transition firing (T_in) - this is the ENTER event
            if (recordTransitions) {
                recordTransitionFiring(
                    enteringTransition,
                    transitionType,
                    tokenId,
                    previousPlace,
                    placeName,
                    null,  // No fork decision for T_in
                    null,  // Join state determined below
                    workflowStartTime,
                    bufferSize,  // Now passed from ServiceThread
                    VersionConstants.getVersionFromSequenceId(tokenId)  // ruleVersion derived from tokenId
                );
            }
            
            // If this token is a fork child, record genealogy
            String parentTokenIdStr = extractParentTokenId(xmlPayload);
            if (parentTokenIdStr != null && recordGenealogy) {
                try {
                    int parentTokenId = Integer.parseInt(parentTokenIdStr);
                    String forkTransition = extractForkTransition(xmlPayload);
                    
                    recordTokenGenealogy(
                        parentTokenId,
                        tokenId,
                        forkTransition,
                        getMonotonicTimestamp(tokenId),
                        calculateWorkflowBase(tokenId)
                    );
                    
                    logger.info("Recorded genealogy: Token " + tokenId + 
                               " is child of " + parentTokenId + 
                               " from fork " + forkTransition);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid parent token ID: " + parentTokenIdStr);
                }
            }
            
            // If this is a join node, handle synchronization
            if ("JoinNode".equals(transitionType) && recordJoins) {
                handleJoinArrival(enteringTransition, tokenId, placeName, workflowStartTime);
            }
            
        } catch (Exception e) {
            logger.error("Error recording token entering " + placeName + 
                        " for token " + tokenId, e);
        }
    }
    
    /**
     * Record token entering a place (T_in transition fired) - Legacy signature for backward compatibility
     * 
     * @deprecated Use the overload with bufferSize, eventGeneratorTimestamp, and sourceEventGenerator parameters instead
     */
    @Deprecated
    public void recordTokenEntering(String xmlPayload, int tokenId, String placeName, 
                                   String nodeType, long workflowStartTime) {
        recordTokenEntering(xmlPayload, tokenId, placeName, nodeType, workflowStartTime, -1, null, null);
    }
    
    // =============================================================================
    // EVENT GENERATOR: Record token creation/injection
    // =============================================================================
    
    /**
     * Record token generated at Event Generator (GENERATED event)
     *
     * This should be called when the Event Generator creates/injects a new token
     * into the workflow. This provides the "birth" event that the animation needs
     * to show tokens appearing at the Event Generator before traveling to T_in.
     *
     * WHEN TO CALL: In ServiceThread or EventGenerator, immediately when a new
     * token is created, BEFORE it is sent to the first place.
     *
     * EVENT FORMAT (in database):
     *   Time=xxx Token=xxx Place=EVENT_GENERATOR Marking=1 Buffer=0
     *   ToPlace=P1_Place TransitionId=EVENT_GENERATOR EventType=GENERATED
     *
     * @param tokenId The newly created token's sequence ID
     * @param eventGeneratorId The ID/name of the event generator (e.g., "EVENT_GENERATOR")
     * @param firstPlaceName The first place the token will travel to (e.g., "P1_Place")
     * @param workflowStartTime The workflow instance start time
     */
    public void recordTokenGenerated(int tokenId, String eventGeneratorId,
                                     String firstPlaceName, long workflowStartTime) {
        // Skip instrumentation for admin/collection workflows (v999)
        if (shouldSkipInstrumentation(tokenId)) {
            logger.debug("Skipping instrumentation for admin token " + tokenId + " (v999)");
            return;
        }
        
        try {
            logger.debug("Token " + tokenId + " GENERATED at " + eventGeneratorId +
                        " -> " + firstPlaceName);
            
            // Use the event generator ID as the transition ID
            // This allows the animator to identify this as an event generator event
            String transitionId = eventGeneratorId != null ? eventGeneratorId : "EVENT_GENERATOR";
            
            // Record the GENERATED event
            if (recordTransitions) {
                // Use monotonic timestamp for consistency
                long timestamp = getMonotonicTimestamp(tokenId);
                
                TreeMap<String, String> record = new TreeMap<>();
                record.put("timestamp", Long.toString(timestamp));
                record.put("transitionId", transitionId);
                record.put("transitionType", "EventGenerator");
                record.put("tokenId", Integer.toString(tokenId));
                record.put("workflowBase", Integer.toString(calculateWorkflowBase(tokenId)));
                record.put("fromPlace", transitionId);  // Token comes FROM event generator
                record.put("toPlace", firstPlaceName != null ? firstPlaceName : "");
                record.put("forkDecision", "");
                record.put("joinState", "");
                record.put("bufferSize", "0");  // No buffer at event generator
                record.put("ruleVersion", VersionConstants.getVersionFromSequenceId(tokenId));
                record.put("eventType", "GENERATED");
                record.put("arcValue", "");
                
                dbWriter.writeTransitionFiring(record);
                
                logger.info("GENERATED: Token " + tokenId + " created at " + transitionId +
                           " -> " + firstPlaceName + " @" + timestamp);
            }
            
        } catch (Exception e) {
            logger.error("Error recording token generated at " + eventGeneratorId +
                        " for token " + tokenId, e);
        }
    }
    
    /**
     * Record token generated at Event Generator - simplified signature
     * Uses default "EVENT_GENERATOR" as the generator ID.
     *
     * @param tokenId The newly created token's sequence ID
     * @param firstPlaceName The first place the token will travel to
     */
    public void recordTokenGenerated(int tokenId, String firstPlaceName) {
        recordTokenGenerated(tokenId, "EVENT_GENERATOR", firstPlaceName, System.currentTimeMillis());
    }
    
    /**
     * Record token arriving at T_in buffer (BUFFERED event)
     * 
     * This is called when a token arrives at a transition's input buffer, BEFORE
     * it enters the place. For JoinNodes, the token waits in the buffer until
     * all required tokens arrive. For EdgeNodes/ForkNodes, this is immediately
     * followed by an ENTER event.
     * 
     * IMPORTANT: This records arrival at T_in, NOT entry into the Place.
     * The token is in the transition's input buffer, waiting to fire.
     * 
     * NEW: If the payload contains an eventGeneratorTimestamp, this means the token
     * came directly from an Event Generator. We record a GENERATED event first to
     * give the animator accurate timing for the token's creation.
     * 
     * @param xmlPayload The incoming XML payload containing transition metadata
     * @param tokenId The token's sequence ID
     * @param placeName The place (service) the token will enter when T_in fires
     * @param nodeType The node type (EdgeNode, ForkNode, JoinNode, etc.)
     * @param workflowStartTime The workflow instance start time
     * @param bufferSize The transition buffer size (queue depth when token arrived)
     */
    public void recordTokenBuffered(String xmlPayload, int tokenId, String placeName, 
            String nodeType, long workflowStartTime, int bufferSize,
            String eventGeneratorTimestamp, String sourceEventGenerator) {     // Skip instrumentation for admin/collection workflows (v999)
        if (shouldSkipInstrumentation(tokenId)) {
            logger.debug("Skipping instrumentation for admin token " + tokenId + " (v999)");
            return;
        }
        
        try {
            logger.debug("Token " + tokenId + " buffered at T_in_" + placeName + " (buffer=" + bufferSize + ")");
            
            // Extract transition metadata from payload
            String previousPlace = extractPreviousPlace(xmlPayload);
            String enteringTransition = extractTransitionId(xmlPayload, buildTInTransitionId(placeName));
            String transitionType = extractTransitionType(xmlPayload, nodeType);
            
            // CHECK FOR EVENT GENERATOR TIMESTAMP
            // If present, this token came directly from an Event Generator
            // Record a GENERATED event BEFORE the BUFFERED event
            if (eventGeneratorTimestamp != null && !eventGeneratorTimestamp.isEmpty()) {
                try {
                    long eventGenTimestamp = Long.parseLong(eventGeneratorTimestamp);
                    
                    // Only record GENERATED if timestamp is non-zero (zero = already processed)
                    if (eventGenTimestamp > 0) {
                        
                        // Use the sourceEventGenerator passed as parameter
                        String generatorId = (sourceEventGenerator != null && !sourceEventGenerator.isEmpty()) 
                            ? sourceEventGenerator : "EVENT_GENERATOR";            
                        logger.info("GENERATED: Token " + tokenId + " from " + sourceEventGenerator + " at " + 
                                   eventGenTimestamp + " -> " + placeName);
                        
                        // Record the GENERATED event with the original timestamp
                        if (recordTransitions) {
                            TreeMap<String, String> record = new TreeMap<>();
                            record.put("timestamp", Long.toString(eventGenTimestamp));
                            record.put("transitionId", sourceEventGenerator);
                            record.put("transitionType", "EventGenerator");
                            record.put("tokenId", Integer.toString(tokenId));
                            record.put("workflowBase", Integer.toString(calculateWorkflowBase(tokenId)));
                            record.put("fromPlace", sourceEventGenerator);
                            record.put("toPlace", placeName);
                            record.put("forkDecision", "");
                            record.put("joinState", "");
                            record.put("bufferSize", "0");
                            record.put("ruleVersion", VersionConstants.getVersionFromSequenceId(tokenId));
                            record.put("eventType", "GENERATED");
                            record.put("arcValue", "");
                            
                            dbWriter.writeTransitionFiring(record);
                        }
                    }
                } catch (NumberFormatException e) {
                    logger.warn("Invalid eventGeneratorTimestamp: " + eventGeneratorTimestamp);
                }
            }
            
            // Record the BUFFERED event (token arrived at T_in, waiting in buffer)
            if (recordTransitions) {
                recordTransitionFiring(
                    enteringTransition,
                    transitionType,
                    tokenId,
                    previousPlace,
                    placeName,
                    null,  // No fork decision for T_in
                    null,  // Join state not applicable yet
                    workflowStartTime,
                    bufferSize,
                    VersionConstants.getVersionFromSequenceId(tokenId),
                    "BUFFERED"  // Explicit event type: arrived at buffer, not yet in place
                );
            }
            
            // If this token is a fork child, record genealogy
            String parentTokenIdStr = extractParentTokenId(xmlPayload);
            if (parentTokenIdStr != null && recordGenealogy) {
                try {
                    int parentTokenId = Integer.parseInt(parentTokenIdStr);
                    String forkTransition = extractForkTransition(xmlPayload);
                    
                    recordTokenGenealogy(
                        parentTokenId,
                        tokenId,
                        forkTransition,
                        getMonotonicTimestamp(tokenId),
                        calculateWorkflowBase(tokenId)
                    );
                    
                    logger.info("Recorded genealogy: Token " + tokenId + 
                               " is child of " + parentTokenId + 
                               " from fork " + forkTransition);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid parent token ID: " + parentTokenIdStr);
                }
            }
            
        } catch (Exception e) {
            logger.error("Error recording token buffered at " + placeName + 
                        " for token " + tokenId, e);
        }
    }
    
    /**
     * Record token actually entering a place (T_in transition FIRED - join complete or no join)
     * 
     * This is called when a token actually enters the place:
     * - For EdgeNode/ForkNode: Called immediately after recordTokenBuffered
     * - For JoinNode: Called ONLY when the join completes (all tokens arrived)
     * 
     * IMPORTANT: For JoinNodes, only the continuation token gets an ENTER event.
     * Consumed tokens get a JOIN_CONSUMED event instead.
     * 
     * @param tokenId The token's sequence ID
     * @param placeName The place the token is entering
     * @param nodeType The node type
     * @param workflowStartTime The workflow instance start time
     */
    public void recordTokenEnteredPlace(int tokenId, String placeName, 
                                        String nodeType, long workflowStartTime) {
        // Skip instrumentation for admin/collection workflows (v999)
        if (shouldSkipInstrumentation(tokenId)) {
            logger.debug("Skipping instrumentation for admin token " + tokenId + " (v999)");
            return;
        }
        
        try {
            logger.debug("Token " + tokenId + " ENTERED place: " + placeName);
            
            String enteringTransition = buildTInTransitionId(placeName);
            
            // Record the ENTER event (token actually entered the place)
            if (recordTransitions) {
                recordTransitionFiring(
                    enteringTransition,
                    nodeType,
                    tokenId,
                    "",     // previousPlace not needed here
                    placeName,
                    null,
                    null,
                    workflowStartTime,
                    -1,     // buffer size not applicable for ENTER
                    VersionConstants.getVersionFromSequenceId(tokenId),
                    "ENTER"  // Explicit event type: token is now IN the place
                );
            }
            
        } catch (Exception e) {
            logger.error("Error recording token entered place " + placeName + 
                        " for token " + tokenId, e);
        }
    }
    
    /**
     * Record token consumed at a join (JOIN_CONSUMED event)
     * 
     * When a join completes, only one token (the continuation token) enters the place.
     * The other tokens are consumed - they cease to exist. This method records that
     * consumption so the animator knows to remove these tokens.
     * 
     * @param tokenId The consumed token's sequence ID
     * @param placeName The place where the join occurred
     * @param continuationTokenId The token that continues (for reference)
     * @param workflowStartTime The workflow instance start time
     */
    public void recordTokenConsumedAtJoin(int tokenId, String placeName, 
                                          int continuationTokenId, long workflowStartTime) {
        // Skip instrumentation for admin/collection workflows (v999)
        if (shouldSkipInstrumentation(tokenId)) {
            return;
        }
        
        try {
            logger.info("Token " + tokenId + " CONSUMED at join " + placeName + 
                       " (continuation token: " + continuationTokenId + ")");
            
            String joinTransition = buildTInTransitionId(placeName);
            
            if (recordTransitions) {
                recordTransitionFiring(
                    joinTransition,
                    "JoinNode",
                    tokenId,
                    "",
                    "JOIN_CONSUMED",  // Special marker for consumed tokens
                    null,
                    "consumed_by_" + continuationTokenId,
                    workflowStartTime,
                    -1,
                    VersionConstants.getVersionFromSequenceId(tokenId),
                    "JOIN_CONSUMED"
                );
            }
            
        } catch (Exception e) {
            logger.error("Error recording token consumed at join " + placeName + 
                        " for token " + tokenId, e);
        }
    }
    
    /**
     * Record token exiting a place (T_out transition about to fire)
     * 
     * Called from ServiceThread.callNextOperation() before publishing to next service
     * 
     * @param tokenId The token's sequence ID
     * @param fromPlace The place (service) the token is leaving
     * @param toPlace The next place (service) the token will enter
     * @param toOperation The operation at the next place
     * @param nodeType The current node type
     * @param decisionValueCollection Fork decision values (if applicable)
     * @return Enhanced XML payload with transition metadata (for future use)
     */
    public String recordTokenExiting(int tokenId, String fromPlace, String toPlace, 
                                    String toOperation, String nodeType,
                                    TreeMap<Integer, String> decisionValueCollection) {
        return recordTokenExitingWithArc(tokenId, fromPlace, toPlace, toOperation, nodeType, 
                                         decisionValueCollection, null);
    }
    
    /**
     * Record token exiting a place (T_out transition about to fire) WITH arc value
     * 
     * Called from ServiceThread for EDGE/XOR guards where the routing decision matters.
     * The arcValue is the actual guard value that was matched (e.g., "true", "false", "approved").
     * 
     * @param tokenId The token's sequence ID
     * @param fromPlace The place (service) the token is leaving
     * @param toPlace The next place (service) the token will enter
     * @param toOperation The operation at the next place
     * @param nodeType The current node type
     * @param decisionValueCollection Fork decision values (if applicable)
     * @param arcValue The actual arc/guard value taken (for EDGE/XOR routing) - can be null
     * @return Enhanced XML payload with transition metadata (for future use)
     */
    public String recordTokenExitingWithArc(int tokenId, String fromPlace, String toPlace, 
                                            String toOperation, String nodeType,
                                            TreeMap<Integer, String> decisionValueCollection,
                                            String arcValue) {
        // Skip instrumentation for admin/collection workflows (v999)
        if (shouldSkipInstrumentation(tokenId)) {
            logger.debug("Skipping instrumentation for admin token " + tokenId + " (v999)");
            return "";
        }
        
        try {
            logger.debug("Token " + tokenId + " exiting place: " + fromPlace + " -> " + toPlace + 
                        (arcValue != null ? " [arc=" + arcValue + "]" : ""));
            
            // Determine exit transition properties
            String exitingTransition = buildTOutTransitionId(fromPlace);
            String transitionType = determineExitTransitionType(nodeType, toPlace);
            
            // Extract fork decision if this is a fork
            String forkDecision = null;
            if ("ForkNode".equals(transitionType) && decisionValueCollection != null) {
                forkDecision = extractForkDecision(decisionValueCollection);
            }
            
            // Record the transition firing (T_out)
            // Note: Buffer size not applicable for T_out - tokens exit, they don't queue
            // arcValue captures the actual routing decision for EDGE/XOR guards
            if (recordTransitions) {
                recordTransitionFiringWithArc(
                    exitingTransition,
                    transitionType,
                    tokenId,
                    fromPlace,
                    toPlace,
                    forkDecision,
                    null,  // Join state not applicable for T_out
                    0,     // Workflow start time not needed here
                    -1,    // bufferSize not applicable for exit
                    VersionConstants.getVersionFromSequenceId(tokenId),  // ruleVersion derived from tokenId
                    arcValue  // The actual arc/guard value taken
                );
            }
            
            // If this is a termination, log it
            if ("TerminateNode".equals(transitionType) || "null".equals(toPlace)) {
                logger.info("Token " + tokenId + " terminating at " + exitingTransition);
            }
            
        } catch (Exception e) {
            logger.error("Error recording token exiting " + fromPlace + 
                        " for token " + tokenId, e);
        }
        
        // Return metadata for enriching outgoing payload (future enhancement)
        return buildTransitionMetadata(tokenId, fromPlace, toPlace);
    }
    
    /**
     * Record fork genealogy - parent to child relationship
     * 
     * Called from ServiceThread when a fork creates child tokens.
     * This enables the analyzer to group tokens by workflow family.
     * 
     * IMPORTANT: This also records a FORK_CONSUMED event for the parent token,
     * signaling that the parent token no longer exists after this fork.
     * 
     * @param parentTokenId The parent token ID (before fork)
     * @param childTokenId The child token ID (after fork)
     * @param forkTransition The transition where fork occurred (e.g., "T_out_P1_Place")
     */
    public void recordForkGenealogy(int parentTokenId, int childTokenId, 
                                    String forkTransition) {
        // Skip instrumentation for admin/collection workflows (v999)
        if (shouldSkipInstrumentation(parentTokenId) || shouldSkipInstrumentation(childTokenId)) {
            logger.debug("Skipping genealogy for admin tokens (v999)");
            return;
        }
        
        if (!recordGenealogy) {
            return;
        }
        
        try {
            int workflowBase = calculateWorkflowBase(parentTokenId);
            
            // Use monotonic timestamp for the fork event
            // This ensures FORK event is after any previous events for the parent
            long forkTimestamp = getMonotonicTimestamp(parentTokenId);
            
            TreeMap<String, String> record = new TreeMap<>();
            record.put("parentTokenId", Integer.toString(parentTokenId));
            record.put("childTokenId", Integer.toString(childTokenId));
            record.put("forkTransitionId", forkTransition);
            record.put("forkTimestamp", Long.toString(forkTimestamp));
            record.put("workflowBase", Integer.toString(workflowBase));
            
            dbWriter.writeTokenGenealogy(record);
            
            logger.info("GENEALOGY: Recorded fork " + parentTokenId + " -> " + childTokenId + 
                       " via " + forkTransition + " (workflowBase=" + workflowBase + ") @" + forkTimestamp);
            
            // Record FORK_CONSUMED event for the parent token
            // This signals that the parent token no longer exists after forking
            // We use a special "synthetic" transition to mark this lifecycle event
            recordParentTokenConsumed(parentTokenId, forkTransition, workflowBase);
            
            // Record FORK event for the CHILD token - this is what animation needs
            // Use monotonic timestamp for the child (initializes its timeline)
            long childTimestamp = getMonotonicTimestamp(childTokenId);
            recordChildTokenCreated(parentTokenId, childTokenId, forkTransition,
                                    childTimestamp, workflowBase);
            
        } catch (Exception e) {
            logger.error("Failed to record fork genealogy: parent=" + parentTokenId + 
                        " child=" + childTokenId, e);
        }
    }
    
    /**
     * Record FORK event when a child token is created.
     * This gives the animation the "birth" event it needs to spawn the token visual.
     */
    private void recordChildTokenCreated(int parentTokenId, int childTokenId,
                                         String forkTransition, long timestamp,
                                         int workflowBase) {
        // Extract place name from transition (T_out_P1_Place -> P1_Place)
        String placeName = "";
        if (forkTransition != null && forkTransition.startsWith("T_out_")) {
            placeName = forkTransition.substring(6);  // P1_Place
        }
        
        // Normalize transitionId to match collector query (T_out_P1, not T_out_P1_Place)
        String normalizedTransitionId = buildTOutTransitionId(placeName);
        String nodeLabel = deriveElementLabel(placeName);  // P1
        
        TreeMap<String, String> record = new TreeMap<>();
        record.put("timestamp", Long.toString(timestamp));
        record.put("transitionId", normalizedTransitionId);  // T_out_P1 (not T_out_P1_Place)
        record.put("transitionType", "ForkNode");
        record.put("tokenId", Integer.toString(childTokenId));
        record.put("workflowBase", Integer.toString(workflowBase));
        record.put("fromPlace", nodeLabel);
        record.put("toPlace", nodeLabel);
        record.put("forkDecision", "");
        record.put("joinState", "parent_" + parentTokenId);
        record.put("bufferSize", "");
        record.put("ruleVersion", VersionConstants.getVersionFromSequenceId(childTokenId));
        record.put("eventType", "FORK");
        
        try {
            dbWriter.writeTransitionFiring(record);
            logger.info("FORK: Child token " + childTokenId + " created from parent " + 
                       parentTokenId + " at " + normalizedTransitionId);
        } catch (Exception e) {
            logger.error("Failed to record FORK for child " + childTokenId, e);
        }
    }
    
    /**
     * Track which parent tokens have already had FORK_CONSUMED recorded
     * (to avoid duplicate events when multiple children are created)
     */
    private final ConcurrentHashMap<String, Boolean> consumedParents = new ConcurrentHashMap<>();
    
    /**
     * Record that a parent token was consumed by a fork
     * Only records once per parent per fork transition
     */
    private void recordParentTokenConsumed(int parentTokenId, String forkTransition, int workflowBase) {
        // Create a unique key for this parent at this fork
        String key = parentTokenId + "_" + forkTransition;
        
        // Only record once (multiple children may trigger this)
        if (consumedParents.putIfAbsent(key, Boolean.TRUE) != null) {
            logger.debug("Parent " + parentTokenId + " already marked as consumed at " + forkTransition);
            return;
        }
        
        // Extract the place name from the fork transition (e.g., "T_out_P1_Place" -> "P1_Place")
        String fromPlace = "";
        if (forkTransition != null && forkTransition.startsWith("T_out_")) {
            fromPlace = forkTransition.substring(6); // Remove "T_out_" prefix
        }
        
        // Use monotonic timestamp for proper event ordering
        long timestamp = getMonotonicTimestamp(parentTokenId);
        
        // Record the FORK_CONSUMED event
        // This tells the animator that this token no longer exists
        TreeMap<String, String> record = new TreeMap<>();
        record.put("timestamp", Long.toString(timestamp));
        record.put("transitionId", forkTransition);
        record.put("transitionType", "ForkConsumed");
        record.put("tokenId", Integer.toString(parentTokenId));
        record.put("workflowBase", Integer.toString(workflowBase));
        record.put("fromPlace", fromPlace);
        record.put("toPlace", "FORK_CONSUMED");  // Special marker
        record.put("forkDecision", "");
        record.put("joinState", "");
        record.put("bufferSize", "");
        record.put("ruleVersion", VersionConstants.getVersionFromSequenceId(parentTokenId));
        record.put("eventType", "FORK_CONSUMED");
        
        try {
            dbWriter.writeTransitionFiring(record);
            logger.info("FORK_CONSUMED: Parent token " + parentTokenId + 
                       " consumed at " + forkTransition + " (workflowBase=" + workflowBase + ") @" + timestamp);
        } catch (Exception e) {
            logger.error("Failed to record FORK_CONSUMED for parent " + parentTokenId, e);
        }
    }
    
    /**
     * Handle join synchronization - determine if token should continue or be consumed
     * 
     * Called from ServiceThread when token arrives at a JoinNode
     * 
     * @param joinPlace The place name where join occurs
     * @param tokenId The arriving token's sequence ID
     * @param requiredCount How many tokens must arrive before join completes
     * @return true if this token should continue, false if consumed by join
     */
    public boolean handleJoinSynchronization(String joinPlace, int tokenId, int requiredCount) {
        // Skip instrumentation for admin/collection workflows (v999)
        // Still return true so the token continues through the workflow
        if (shouldSkipInstrumentation(tokenId)) {
            logger.debug("Skipping join synchronization for admin token " + tokenId + " (v999)");
            return true;
        }
        
        try {
            String joinTransition = buildTInTransitionId(joinPlace);
            int workflowBase = calculateWorkflowBase(tokenId);
            String joinKey = joinTransition + "_" + workflowBase;
            
            logger.info("Join synchronization: Token " + tokenId + 
                       " at " + joinTransition + 
                       " (workflowBase=" + workflowBase + ", required=" + requiredCount + ")");
            
            // Get or create join state
            JoinState joinState = activeJoins.computeIfAbsent(joinKey, 
                k -> new JoinState(joinTransition, workflowBase, requiredCount));
            
            // Register this token's arrival
            boolean isComplete = joinState.addToken(tokenId);
            
            // Record in database
            if (recordJoins) {
                registerJoinContribution(
                    joinTransition,
                    workflowBase,
                    tokenId,
                    requiredCount,
                    joinState.getTokenCount(),
                    isComplete ? "COMPLETE" : "WAITING"
                );
            }
            
            if (isComplete) {
                // Join complete - get continuation token (lowest ID)
                int continuationToken = joinState.getContinuationToken();
                
                logger.info("Join COMPLETE at " + joinTransition + 
                           " for workflowBase " + workflowBase + 
                           ", continuation token: " + continuationToken);
                
                // Record join completion
                if (recordJoins) {
                    recordJoinCompletion(
                        joinTransition,
                        workflowBase,
                        joinState.getAllTokens(),
                        continuationToken
                    );
                }
                
                // Clean up join state
                activeJoins.remove(joinKey);
                
                // Only continuation token proceeds
                if (tokenId == continuationToken) {
                    logger.info("Token " + tokenId + " continues through join");
                    return true;
                } else {
                    logger.info("Token " + tokenId + " CONSUMED at join");
                    return false;
                }
            } else {
                logger.info("Token " + tokenId + " waiting at join (" + 
                           joinState.getTokenCount() + "/" + requiredCount + " arrived)");
                // In asynchronous model, token would wait here
                // In your synchronous model, this might not happen
                return true;
            }
            
        } catch (Exception e) {
            logger.error("Error in join synchronization for token " + tokenId + 
                        " at place " + joinPlace, e);
            // On error, allow token to proceed (fail-open)
            return true;
        }
    }
    
    // =============================================================================
    // CORE RECORDING METHODS (Private - called by entry points)
    // =============================================================================
    
    /**
     * Record a transition firing event
     * 
     * TIMING: Uses getMonotonicTimestamp() to ensure events are recorded in
     * strictly increasing order per token, preventing race conditions.
     * 
     * @param eventType The type of event: ENTER, EXIT, FORK_CONSUMED, TERMINATE
     * @param arcValue The actual arc/guard value taken (for EDGE/XOR routing decisions)
     */
    private void recordTransitionFiring(String transitionId, String transitionType,
                                       int tokenId, String fromPlace, String toPlace,
                                       String forkDecision, String joinState,
                                       long workflowStartTime, int bufferSize, String ruleVersion,
                                       String eventType, String arcValue) {
        try {
            // Use monotonic timestamp to prevent race conditions
            long timestamp = getMonotonicTimestamp(tokenId);
            
            TreeMap<String, String> record = new TreeMap<>();
            record.put("timestamp", Long.toString(timestamp));
            record.put("transitionId", transitionId);
            record.put("transitionType", transitionType != null ? transitionType : "EdgeNode");
            record.put("tokenId", Integer.toString(tokenId));
            record.put("workflowBase", Integer.toString(calculateWorkflowBase(tokenId)));
            record.put("fromPlace", fromPlace != null ? fromPlace : "");
            record.put("toPlace", toPlace != null ? toPlace : "");
            record.put("forkDecision", forkDecision != null ? forkDecision : "");
            record.put("joinState", joinState != null ? joinState : "");
			record.put("bufferSize", bufferSize >= 0 ? Integer.toString(bufferSize) : "");
			record.put("ruleVersion", ruleVersion != null ? ruleVersion : "");
            record.put("eventType", eventType != null ? eventType : "");
            record.put("arcValue", arcValue != null ? arcValue : "");
            
            dbWriter.writeTransitionFiring(record);
            
            logger.debug("Recorded transition: " + transitionId + 
                        " (" + transitionType + ") " +
                        "token=" + tokenId + " " +
                        fromPlace + " -> " + toPlace +
                        " [" + eventType + "]" +
                        (arcValue != null && !arcValue.isEmpty() ? " arc=" + arcValue : "") +
                        (bufferSize >= 0 ? " buffer=" + bufferSize : "") +
                        " @" + timestamp);
            
        } catch (Exception e) {
            logger.error("Failed to record transition firing: " + transitionId, e);
        }
    }
    
    /**
     * Record a transition firing event (backward compatible - no arcValue)
     */
    private void recordTransitionFiring(String transitionId, String transitionType,
                                       int tokenId, String fromPlace, String toPlace,
                                       String forkDecision, String joinState,
                                       long workflowStartTime, int bufferSize, String ruleVersion,
                                       String eventType) {
        recordTransitionFiring(transitionId, transitionType, tokenId, fromPlace, toPlace,
                              forkDecision, joinState, workflowStartTime, bufferSize, ruleVersion, 
                              eventType, null);
    }
    
    /**
     * Record a transition firing event (backward compatible - defaults eventType based on transition)
     */
    private void recordTransitionFiring(String transitionId, String transitionType,
                                       int tokenId, String fromPlace, String toPlace,
                                       String forkDecision, String joinState,
                                       long workflowStartTime, int bufferSize, String ruleVersion) {
        recordTransitionFiringWithArc(transitionId, transitionType, tokenId, fromPlace, toPlace,
                              forkDecision, joinState, workflowStartTime, bufferSize, ruleVersion, null);
    }
    
    /**
     * Record a transition firing event with arcValue - determines eventType automatically
     * This is the preferred method for recording T_out transitions with arc values
     */
    private void recordTransitionFiringWithArc(String transitionId, String transitionType,
                                       int tokenId, String fromPlace, String toPlace,
                                       String forkDecision, String joinState,
                                       long workflowStartTime, int bufferSize, String ruleVersion,
                                       String arcValue) {
        // Determine eventType from transitionId
        String eventType;
        if (transitionId != null && transitionId.startsWith("T_in_")) {
            eventType = "ENTER";
        } else if (transitionId != null && transitionId.startsWith("T_out_")) {
            if ("TerminateNode".equals(transitionType) || toPlace == null || "null".equals(toPlace) || "TERMINATE".equals(toPlace)) {
                eventType = "TERMINATE";
            } else {
                eventType = "EXIT";
            }
        } else {
            eventType = "";
        }
        recordTransitionFiring(transitionId, transitionType, tokenId, fromPlace, toPlace,
                              forkDecision, joinState, workflowStartTime, bufferSize, ruleVersion, eventType, arcValue);
    }
    
    /**
     * Record token genealogy (parent-child relationship from fork)
     */
    private void recordTokenGenealogy(int parentTokenId, int childTokenId,
                                     String forkTransition, long timestamp,
                                     int workflowBase) {
        try {
            TreeMap<String, String> record = new TreeMap<>();
            record.put("parentTokenId", Integer.toString(parentTokenId));
            record.put("childTokenId", Integer.toString(childTokenId));
            record.put("forkTransitionId", forkTransition);
            record.put("forkTimestamp", Long.toString(timestamp));
            record.put("workflowBase", Integer.toString(workflowBase));
            
            dbWriter.writeTokenGenealogy(record);
            
            logger.debug("Recorded genealogy: parent=" + parentTokenId + 
                        " -> child=" + childTokenId + 
                        " via " + forkTransition);
            
        } catch (Exception e) {
            logger.error("Failed to record token genealogy: parent=" + parentTokenId + 
                        " child=" + childTokenId, e);
        }
    }
    
    /**
     * Register a token's contribution to a join
     */
    private void registerJoinContribution(String joinTransitionId, int workflowBase,
                                         int tokenId, int requiredCount, 
                                         int currentCount, String status) {
        try {
            // Use monotonic timestamp for consistent ordering
            long timestamp = getMonotonicTimestamp(tokenId);
            
            TreeMap<String, String> record = new TreeMap<>();
            record.put("joinTransitionId", joinTransitionId);
            record.put("workflowBase", Integer.toString(workflowBase));
            record.put("tokenId", Integer.toString(tokenId));
            record.put("arrivalTimestamp", Long.toString(timestamp));
            record.put("requiredCount", Integer.toString(requiredCount));
            record.put("currentCount", Integer.toString(currentCount));
            record.put("status", status);
            record.put("continuationTokenId", ""); // Set on completion
            
            dbWriter.writeJoinSynchronization(record);
            
            logger.debug("Registered join contribution: " + joinTransitionId + 
                        " token=" + tokenId + 
                        " (" + currentCount + "/" + requiredCount + ") " +
                        status + " @" + timestamp);
            
        } catch (Exception e) {
            logger.error("Failed to register join contribution: " + joinTransitionId + 
                        " token=" + tokenId, e);
        }
    }
    
    /**
     * Record join completion with continuation token
     */
    private void recordJoinCompletion(String joinTransitionId, int workflowBase,
                                     List<Integer> contributingTokens, 
                                     int continuationToken) {
        try {
            // Update all contributing tokens' records with continuation token
            dbWriter.updateJoinCompletion(joinTransitionId, workflowBase, continuationToken);
            
            logger.info("Join completion recorded: " + joinTransitionId + 
                       " workflowBase=" + workflowBase + 
                       " tokens=" + contributingTokens + 
                       " -> continues with " + continuationToken);
            
        } catch (Exception e) {
            logger.error("Failed to record join completion: " + joinTransitionId, e);
        }
    }
    
    // =============================================================================
    // HELPER METHODS FOR JOIN MANAGEMENT
    // =============================================================================
    
    /**
     * Handle token arrival at a join point
     */
    private void handleJoinArrival(String joinTransition, int tokenId, 
                                  String placeName, long workflowStartTime) {
        try {
            int workflowBase = calculateWorkflowBase(tokenId);
            
            // Query how many tokens are required for this join (from DOT metadata)
            // For now, default to 3 (can be enhanced to read from DOT)
            int requiredCount = 3; // TODO: Read from DOT file metadata
            
            // This will be handled more completely in handleJoinSynchronization
            // For now, just log
            logger.debug("Token " + tokenId + " arrived at join " + joinTransition);
            
        } catch (Exception e) {
            logger.error("Error handling join arrival for token " + tokenId, e);
        }
    }
    
    // =============================================================================
    // XML PAYLOAD EXTRACTION METHODS
    // =============================================================================
    
    /**
     * Extract the previous place from XML payload
     */
    private String extractPreviousPlace(String xmlPayload) {
        try {
            String previous = xph.findXMLItem(xmlPayload, "//transition/previousPlace");
            return previous != null ? previous : "";
        } catch (Exception e) {
            logger.debug("Could not extract previousPlace from payload");
            return "";
        }
    }
    
    /**
     * Extract transition ID from XML payload (with fallback)
     */
    private String extractTransitionId(String xmlPayload, String fallback) {
        try {
            String transitionId = xph.findXMLItem(xmlPayload, "//transition/transitionId");
            return transitionId != null ? transitionId : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
    
    /**
     * Extract transition type from XML payload (with fallback)
     */
    private String extractTransitionType(String xmlPayload, String fallback) {
        try {
            String type = xph.findXMLItem(xmlPayload, "//transition/transitionType");
            return type != null ? type : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
    
    /**
     * Extract parent token ID (for fork children)
     */
    private String extractParentTokenId(String xmlPayload) {
        try {
            return xph.findXMLItem(xmlPayload, "//transition/parentTokenId");
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Extract fork transition that created this child token
     */
    private String extractForkTransition(String xmlPayload) {
        try {
            String forkTrans = xph.findXMLItem(xmlPayload, "//transition/forkTransition");
            return forkTrans != null ? forkTrans : "UNKNOWN_FORK";
        } catch (Exception e) {
            return "UNKNOWN_FORK";
        }
    }
    
    /**
     * Extract eventGeneratorTimestamp from payload.
     * This timestamp indicates when the token was created at the Event Generator.
     * Used to record accurate GENERATED events for animation.
     */
    private String extractEventGeneratorTimestamp(String xmlPayload) {
        try {
            return xph.findXMLItem(xmlPayload, "//monitorData/eventGeneratorTimestamp");
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * DEPRECATED: sourceEventGenerator is now passed as a parameter from ServiceThread
     * which extracts it from monitorDataMap. This method is kept for backward compatibility
     * but should not be used for new code.
     * 
     * Extract sourceEventGenerator from payload.
     * This identifies which event generator created the token (e.g., TRIAGE_EVENTGENERATOR).
     * Used to track token source for workflows with multiple event generators.
     */
    @Deprecated
    private String extractSourceEventGenerator(String xmlPayload) {
        try {
            String source = xph.findXMLItem(xmlPayload, "//monitorData/sourceEventGenerator");
            if (source == null || source.isEmpty()) {
                logger.warn("extractSourceEventGenerator: sourceEventGenerator not found in payload - use parameter from ServiceThread instead");
                return "EVENT_GENERATOR";
            }
            return source;
        } catch (Exception e) {
            logger.warn("extractSourceEventGenerator failed: " + e.getMessage());
            return "EVENT_GENERATOR";
        }
    }
    
    /**
     * Extract fork decision from decision value collection
     */
    private String extractForkDecision(TreeMap<Integer, String> decisionValueCollection) {
        if (decisionValueCollection == null || decisionValueCollection.isEmpty()) {
            return "";
        }
        // Concatenate all decision values
        StringBuilder sb = new StringBuilder();
        for (String value : decisionValueCollection.values()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(value);
        }
        return sb.toString();
    }
    
    // =============================================================================
    // UTILITY METHODS
    // =============================================================================
    
    /**
     * Derive the topology element label (node name) from a service name.
     * 
     * ============================================================================
     * TOPOLOGY NAMING CONVENTION
     * ============================================================================
     * 
     * The Petri Net topology follows a strict naming convention that separates
     * the logical topology (nodes, transitions) from the physical implementation
     * (services).
     * 
     * PLACES (Nodes):
     *   - Named with short identifiers: P1, P2, P3, Monitor, etc.
     *   - These appear in the workflow JSON as element labels
     *   - Example: "label": "P1"
     * 
     * SERVICES (Implementations):
     *   - Named as: {NodeName}_Place
     *   - These are the actual Java classes that implement the place behavior
     *   - Example: "service": "P1_Place"
     * 
     * TRANSITIONS:
     *   - Input transitions:  T_in_{NodeName}   (e.g., T_in_P1, T_in_Monitor)
     *   - Output transitions: T_out_{NodeName}  (e.g., T_out_P1, T_out_Monitor)
     *   - These connect places in the topology
     * 
     * ============================================================================
     * MAPPING RULE
     * ============================================================================
     * 
     *   Service Name = Node Name + "_Place"
     *   Node Name    = Service Name - "_Place"
     * 
     * Examples:
     *   P1_Place      -> P1
     *   P2_Place      -> P2
     *   MonitorService -> Monitor
     * 
     * This convention MUST be followed for all services. If a service does not
     * end with "_Place", it violates the convention and should be renamed.
     * 
     * ============================================================================
     * WHY THIS MATTERS
     * ============================================================================
     * 
     * The topology JSON defines transitions using node names (T_in_P1, T_out_P1).
     * The runtime uses service names (P1_Place) for invocation.
     * 
     * When recording instrumentation data, we must convert service names back
     * to node names so that:
     *   1. Transition IDs in the database match the topology
     *   2. The analyzer can correlate events with the workflow structure
     *   3. The animator can look up positions and routes correctly
     * 
     * Failure to follow this convention results in mismatched transition IDs
     * (e.g., T_in_P1_Place instead of T_in_P1), which breaks topology lookups
     * and causes incorrect animation behavior.
     * 
     * ============================================================================
     * 
     * @param serviceName The runtime service name (e.g., "P1_Place")
     * @return The topology node name (e.g., "P1")
     * @throws IllegalArgumentException if serviceName doesn't follow convention (logged, not thrown)
     */
    private String deriveElementLabel(String serviceName) {
        if (serviceName == null || serviceName.isEmpty()) {
            return serviceName;
        }
        
        // Apply the canonical rule: strip "_Place" suffix
        if (serviceName.endsWith("_Place")) {
            return serviceName.substring(0, serviceName.length() - "_Place".length());
        }
        
        // Service name doesn't follow convention - log warning but continue
        // This allows the system to function while flagging the issue
        logger.warn("NAMING CONVENTION VIOLATION: Service '" + serviceName + 
                   "' does not end with '_Place'. Expected format: {NodeName}_Place. " +
                   "Transition IDs may not match topology. Please rename the service.");
        return serviceName;
    }
    
    /**
     * Build T_in transition ID from service name.
     * 
     * Converts service name to node name and constructs the input transition ID
     * that matches the topology definition.
     * 
     * @param serviceName The runtime service name (e.g., "P1_Place")
     * @return Transition ID matching topology (e.g., "T_in_P1")
     */
    private String buildTInTransitionId(String serviceName) {
        return "T_in_" + deriveElementLabel(serviceName);
    }
    
    /**
     * Build T_out transition ID from service name.
     * 
     * Converts service name to node name and constructs the output transition ID
     * that matches the topology definition.
     * 
     * @param serviceName The runtime service name (e.g., "P1_Place")
     * @return Transition ID matching topology (e.g., "T_out_P1")
     */
    private String buildTOutTransitionId(String serviceName) {
        return "T_out_" + deriveElementLabel(serviceName);
    }
    
    /**
     * Calculate workflow base from sequence ID (version-aware)
     */
    private int calculateWorkflowBase(int sequenceID) {
        return VersionConstants.getWorkflowBaseFromSequenceId(sequenceID);
    }
    
    /**
     * Determine exit transition type based on context
     */
    private String determineExitTransitionType(String nodeType, String toPlace) {
        if (toPlace == null || "null".equals(toPlace)) {
            return "TerminateNode";
        }
        // TODO: Could query DOT metadata to determine if next is JoinNode, ForkNode
        // For now, use nodeType if available
        return nodeType != null ? nodeType : "EdgeNode";
    }
    
    /**
     * Build transition metadata for XML payload enrichment
     */
    private String buildTransitionMetadata(int tokenId, String fromPlace, String toPlace) {
        // Future enhancement: build XML fragment with transition metadata
        // to be inserted into outgoing payload
        return "";
    }
    
    // =============================================================================
    // CONFIGURATION METHODS
    // =============================================================================
    
    public void setRecordTransitions(boolean enabled) {
        this.recordTransitions = enabled;
        logger.info("Transition recording: " + enabled);
    }
    
    public void setRecordGenealogy(boolean enabled) {
        this.recordGenealogy = enabled;
        logger.info("Genealogy recording: " + enabled);
    }
    
    public void setRecordJoins(boolean enabled) {
        this.recordJoins = enabled;
        logger.info("Join recording: " + enabled);
    }
    
    // =============================================================================
    // INNER CLASS: JoinState (for tracking join synchronization)
    // =============================================================================
    
    /**
     * Tracks the state of a join synchronization point
     */
    private static class JoinState {
        private final String joinTransitionId;
        private final int workflowBase;
        private final int requiredCount;
        private final List<Integer> arrivedTokens;
        private final long createdTimestamp;
        
        public JoinState(String joinTransitionId, int workflowBase, int requiredCount) {
            this.joinTransitionId = joinTransitionId;
            this.workflowBase = workflowBase;
            this.requiredCount = requiredCount;
            this.arrivedTokens = new ArrayList<>();
            this.createdTimestamp = System.currentTimeMillis();
        }
        
        /**
         * Add a token to this join
         * @return true if join is now complete
         */
        public synchronized boolean addToken(int tokenId) {
            if (!arrivedTokens.contains(tokenId)) {
                arrivedTokens.add(tokenId);
            }
            return arrivedTokens.size() >= requiredCount;
        }
        
        /**
         * Get the continuation token (lowest ID)
         */
        public synchronized int getContinuationToken() {
            if (arrivedTokens.isEmpty()) {
                throw new IllegalStateException("No tokens at join");
            }
            return arrivedTokens.stream().min(Integer::compare).orElse(arrivedTokens.get(0));
        }
        
        public synchronized int getTokenCount() {
            return arrivedTokens.size();
        }
        
        public synchronized List<Integer> getAllTokens() {
            return new ArrayList<>(arrivedTokens);
        }
        
        public boolean isExpired() {
            // Expire after 5 minutes (in case join never completes)
            return (System.currentTimeMillis() - createdTimestamp) > 300000;
        }
    }
}