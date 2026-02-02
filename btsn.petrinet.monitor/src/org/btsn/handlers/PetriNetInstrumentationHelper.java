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
     */
    public void recordTokenEntering(String xmlPayload, int tokenId, String placeName, 
                                   String nodeType, long workflowStartTime, int bufferSize) {
        try {
            logger.debug("Token " + tokenId + " entering place: " + placeName + " (buffer=" + bufferSize + ")");
            
            // Extract transition metadata from payload
            String previousPlace = extractPreviousPlace(xmlPayload);
            String enteringTransition = extractTransitionId(xmlPayload, "T_in_" + placeName);
            String transitionType = extractTransitionType(xmlPayload, nodeType);
            
            // Record the transition firing (T_in)
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
                    "v001"  // ruleVersion - TODO: get from ServiceThread
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
                        System.currentTimeMillis(),
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
     * @deprecated Use the overload with bufferSize parameter instead
     */
    @Deprecated
    public void recordTokenEntering(String xmlPayload, int tokenId, String placeName, 
                                   String nodeType, long workflowStartTime) {
        recordTokenEntering(xmlPayload, tokenId, placeName, nodeType, workflowStartTime, -1);
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
        try {
            logger.debug("Token " + tokenId + " exiting place: " + fromPlace + " -> " + toPlace);
            
            // Determine exit transition properties
            String exitingTransition = "T_out_" + fromPlace;
            String transitionType = determineExitTransitionType(nodeType, toPlace);
            
            // Extract fork decision if this is a fork
            String forkDecision = null;
            if ("ForkNode".equals(transitionType) && decisionValueCollection != null) {
                forkDecision = extractForkDecision(decisionValueCollection);
            }
            
            // Record the transition firing (T_out)
            // Note: Buffer size not applicable for T_out - tokens exit, they don't queue
            if (recordTransitions) {
                recordTransitionFiring(
                    exitingTransition,
                    transitionType,
                    tokenId,
                    fromPlace,
                    toPlace,
                    forkDecision,
                    null,  // Join state not applicable for T_out
                    0,     // Workflow start time not needed here
                    -1,    // bufferSize not applicable for exit
                    "v001"  // ruleVersion - TODO: get from ServiceThread
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
     * @param parentTokenId The parent token ID (before fork)
     * @param childTokenId The child token ID (after fork)
     * @param forkTransition The transition where fork occurred (e.g., "T_out_P1_Place")
     */
    public void recordForkGenealogy(int parentTokenId, int childTokenId, 
                                    String forkTransition) {
        if (!recordGenealogy) {
            return;
        }
        
        try {
            int workflowBase = calculateWorkflowBase(parentTokenId);
            
            TreeMap<String, String> record = new TreeMap<>();
            record.put("parentTokenId", Integer.toString(parentTokenId));
            record.put("childTokenId", Integer.toString(childTokenId));
            record.put("forkTransitionId", forkTransition);
            record.put("forkTimestamp", Long.toString(System.currentTimeMillis()));
            record.put("workflowBase", Integer.toString(workflowBase));
            
            dbWriter.writeTokenGenealogy(record);
            
            logger.info("GENEALOGY: Recorded fork " + parentTokenId + " -> " + childTokenId + 
                       " via " + forkTransition + " (workflowBase=" + workflowBase + ")");
            
        } catch (Exception e) {
            logger.error("Failed to record fork genealogy: parent=" + parentTokenId + 
                        " child=" + childTokenId, e);
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
        try {
            String joinTransition = "T_in_" + joinPlace;
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
     */
    private void recordTransitionFiring(String transitionId, String transitionType,
                                       int tokenId, String fromPlace, String toPlace,
                                       String forkDecision, String joinState,
                                       long workflowStartTime, int bufferSize, String ruleVersion) {
        try {
            TreeMap<String, String> record = new TreeMap<>();
            record.put("timestamp", Long.toString(System.currentTimeMillis()));
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
            
            dbWriter.writeTransitionFiring(record);
            
            logger.debug("Recorded transition: " + transitionId + 
                        " (" + transitionType + ") " +
                        "token=" + tokenId + " " +
                        fromPlace + " -> " + toPlace +
                        (bufferSize >= 0 ? " buffer=" + bufferSize : ""));
            
        } catch (Exception e) {
            logger.error("Failed to record transition firing: " + transitionId, e);
        }
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
            TreeMap<String, String> record = new TreeMap<>();
            record.put("joinTransitionId", joinTransitionId);
            record.put("workflowBase", Integer.toString(workflowBase));
            record.put("tokenId", Integer.toString(tokenId));
            record.put("arrivalTimestamp", Long.toString(System.currentTimeMillis()));
            record.put("requiredCount", Integer.toString(requiredCount));
            record.put("currentCount", Integer.toString(currentCount));
            record.put("status", status);
            record.put("continuationTokenId", ""); // Set on completion
            
            dbWriter.writeJoinSynchronization(record);
            
            logger.debug("Registered join contribution: " + joinTransitionId + 
                        " token=" + tokenId + 
                        " (" + currentCount + "/" + requiredCount + ") " +
                        status);
            
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