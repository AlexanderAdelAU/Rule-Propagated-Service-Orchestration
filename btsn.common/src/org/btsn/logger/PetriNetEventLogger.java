package org.btsn.logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.log4j.Logger;

/**
 * Petri Net Event Logger
 * 
 * Centralized logger for all Petri Net events. Logs events for:
 * - Place lifecycle (creation, state changes)
 * - Token flow (arrival, holding, departure)
 * - Marking changes (M(P) updates)
 * - Capacity violations
 * - Validation checks
 * - Errors
 * 
 * Events can be used for:
 * - Visualization of token flow
 * - Petri Net analysis (reachability, deadlock, liveness)
 * - Debugging
 * - Performance analysis
 * 
 * This is a singleton - all places report to the same logger instance.
 * 
 * @author ACameron
 */
public class PetriNetEventLogger {
    
    private static PetriNetEventLogger instance;
    private static final Logger logger = Logger.getLogger(PetriNetEventLogger.class);
    
    // Event storage for analysis
    private List<PetriNetEvent> eventHistory = new ArrayList<>();
    private ConcurrentHashMap<String, PlaceState> placeStates = new ConcurrentHashMap<>();
    
    private boolean enableEventStorage = true;
    private boolean enableLogging = true;
    
    /**
     * Private constructor for singleton
     */
    private PetriNetEventLogger() {
        logger.info("=== PETRI NET EVENT LOGGER INITIALIZED ===");
    }
    
    /**
     * Get singleton instance
     */
    public static synchronized PetriNetEventLogger getInstance() {
        if (instance == null) {
            instance = new PetriNetEventLogger();
        }
        return instance;
    }
    
    // ========== Place Lifecycle Events ==========
    
    /**
     * Log place creation
     */
    public void logPlaceCreated(String placeId, String sequenceId, String placeType, int capacity) {
        String message = String.format(
            "PLACE_CREATED: placeId=%s, sequenceId=%s, type=%s, capacity=%d",
            placeId, sequenceId, placeType, capacity
        );
        log(message);
        
        // Track place state
        placeStates.put(placeId, new PlaceState(placeId, capacity));
        
        storeEvent(new PetriNetEvent("PLACE_CREATED", placeId, sequenceId, message));
    }
    
    /**
     * Log place execution completed
     */
    public void logPlaceExecution(String placeId, String sequenceId, long executionTime, Object result) {
        String message = String.format(
            "PLACE_EXECUTION: placeId=%s, sequenceId=%s, executionTime=%dms, result=%s",
            placeId, sequenceId, executionTime, result
        );
        log(message);
        storeEvent(new PetriNetEvent("PLACE_EXECUTION", placeId, sequenceId, message));
    }
    
    /**
     * Log place processing (token being held)
     */
    public void logPlaceProcessing(String placeId, String sequenceId, String tokenId) {
        String message = String.format(
            "PLACE_PROCESSING: placeId=%s, sequenceId=%s, tokenId=%s",
            placeId, sequenceId, tokenId
        );
        log(message);
        storeEvent(new PetriNetEvent("PLACE_PROCESSING", placeId, sequenceId, message));
    }
    
    /**
     * Log place processing error
     */
    public void logPlaceProcessingError(String placeId, String sequenceId, Exception e) {
        String message = String.format(
            "PLACE_PROCESSING_ERROR: placeId=%s, sequenceId=%s, error=%s",
            placeId, sequenceId, e.getMessage()
        );
        logError(message, e);
        storeEvent(new PetriNetEvent("PLACE_ERROR", placeId, sequenceId, message));
    }
    
    /**
     * Log place error
     */
    public void logPlaceError(String placeId, String sequenceId, Exception e) {
        String message = String.format(
            "PLACE_ERROR: placeId=%s, sequenceId=%s, error=%s",
            placeId, sequenceId, e.getMessage()
        );
        logError(message, e);
        storeEvent(new PetriNetEvent("PLACE_ERROR", placeId, sequenceId, message));
    }
    
    // ========== Token Flow Events ==========
    
    /**
     * Log token arrival at place
     */
    public void logTokenArrival(String placeId, String sequenceId, Object tokenInfo) {
        String message = String.format(
            "TOKEN_ARRIVAL: placeId=%s, sequenceId=%s, token=%s",
            placeId, sequenceId, tokenInfo
        );
        log(message);
        storeEvent(new PetriNetEvent("TOKEN_ARRIVAL", placeId, sequenceId, message));
    }
    
    /**
     * Log token being held in place
     */
    public void logTokenHeld(String placeId, String sequenceId, Object tokenInfo, int marking, int capacity) {
        String message = String.format(
            "TOKEN_HELD: placeId=%s, sequenceId=%s, token=%s, M(P)=%d, capacity=%d",
            placeId, sequenceId, tokenInfo, marking, capacity
        );
        log(message);
        
        // Update place state
        PlaceState state = placeStates.get(placeId);
        if (state != null) {
            state.setMarking(marking);
        }
        
        storeEvent(new PetriNetEvent("TOKEN_HELD", placeId, sequenceId, message));
    }
    
    /**
     * Log token departure from place
     */
    public void logTokenDeparture(String placeId, String sequenceId, Object tokenInfo, String annotation) {
        String message = String.format(
            "TOKEN_DEPARTURE: placeId=%s, sequenceId=%s, token=%s, annotation=%s",
            placeId, sequenceId, tokenInfo, annotation
        );
        log(message);
        storeEvent(new PetriNetEvent("TOKEN_DEPARTURE", placeId, sequenceId, message));
    }
    
    /**
     * Log token released from place
     */
    public void logTokenReleased(String placeId, String sequenceId, Object tokenInfo, int marking) {
        String message = String.format(
            "TOKEN_RELEASED: placeId=%s, sequenceId=%s, token=%s, M(P)=%d",
            placeId, sequenceId, tokenInfo, marking
        );
        log(message);
        
        // Update place state
        PlaceState state = placeStates.get(placeId);
        if (state != null) {
            state.setMarking(marking);
        }
        
        storeEvent(new PetriNetEvent("TOKEN_RELEASED", placeId, sequenceId, message));
    }
    
    // ========== Marking Events ==========
    
    /**
     * Log marking change
     */
    public void logMarkingChange(String placeId, String sequenceId, int newMarking, String reason) {
        String message = String.format(
            "MARKING_CHANGE: placeId=%s, sequenceId=%s, M(P)=%d, reason=%s",
            placeId, sequenceId, newMarking, reason
        );
        log(message);
        
        // Update place state
        PlaceState state = placeStates.get(placeId);
        if (state != null) {
            state.setMarking(newMarking);
        }
        
        storeEvent(new PetriNetEvent("MARKING_CHANGE", placeId, sequenceId, message));
    }
    
    // ========== Capacity Events ==========
    
    /**
     * Log capacity check
     */
    public void logCapacityCheck(String placeId, String sequenceId, int marking, int capacity, boolean hasCapacity) {
        String message = String.format(
            "CAPACITY_CHECK: placeId=%s, sequenceId=%s, M(P)=%d, capacity=%d, hasCapacity=%b",
            placeId, sequenceId, marking, capacity, hasCapacity
        );
        log(message);
        storeEvent(new PetriNetEvent("CAPACITY_CHECK", placeId, sequenceId, message));
    }
    
    /**
     * Log capacity violation
     */
    public void logCapacityViolation(String placeId, String sequenceId, int marking, int capacity) {
        String message = String.format(
            "CAPACITY_VIOLATION: placeId=%s, sequenceId=%s, M(P)=%d, capacity=%d [REJECTED]",
            placeId, sequenceId, marking, capacity
        );
        logWarn(message);
        storeEvent(new PetriNetEvent("CAPACITY_VIOLATION", placeId, sequenceId, message));
    }
    
    // ========== Validation Events ==========
    
    /**
     * Log version validation
     */
    public void logVersionValidation(String placeId, String sequenceId, String tokenVersion, 
                                     String expectedVersion, boolean valid) {
        String message = String.format(
            "VERSION_VALIDATION: placeId=%s, sequenceId=%s, tokenVersion=%s, expectedVersion=%s, valid=%b",
            placeId, sequenceId, tokenVersion, expectedVersion, valid
        );
        log(message);
        storeEvent(new PetriNetEvent("VERSION_VALIDATION", placeId, sequenceId, message));
    }
    
    /**
     * Log validity window check
     */
    public void logValidityWindowCheck(String placeId, String sequenceId, long currentTime, 
                                       long notAfter, boolean valid) {
        String message = String.format(
            "VALIDITY_WINDOW_CHECK: placeId=%s, sequenceId=%s, currentTime=%d, notAfter=%d, valid=%b",
            placeId, sequenceId, currentTime, notAfter, valid
        );
        log(message);
        storeEvent(new PetriNetEvent("VALIDITY_WINDOW_CHECK", placeId, sequenceId, message));
    }
    
    /**
     * Log token ID validation
     */
    public void logTokenIdValidation(String placeId, String sequenceId, String tokenId, 
                                     String version, boolean valid) {
        String message = String.format(
            "TOKEN_ID_VALIDATION: placeId=%s, sequenceId=%s, tokenId=%s, version=%s, valid=%b",
            placeId, sequenceId, tokenId, version, valid
        );
        log(message);
        storeEvent(new PetriNetEvent("TOKEN_ID_VALIDATION", placeId, sequenceId, message));
    }
    
    /**
     * Log ID space violation
     */
    public void logIdSpaceViolation(String placeId, String sequenceId, String tokenId, 
                                    String version, String reason) {
        String message = String.format(
            "ID_SPACE_VIOLATION: placeId=%s, sequenceId=%s, tokenId=%s, version=%s, reason=%s",
            placeId, sequenceId, tokenId, version, reason
        );
        logWarn(message);
        storeEvent(new PetriNetEvent("ID_SPACE_VIOLATION", placeId, sequenceId, message));
    }
    
    // ========== Join Events (for ServiceThread) ==========
    
    /**
     * Log join input received
     */
    public void logJoinInputReceived(String placeId, String sequenceId, String inputName, String inputValue) {
        String message = String.format(
            "JOIN_INPUT_RECEIVED: placeId=%s, sequenceId=%s, input=%s, value=%s",
            placeId, sequenceId, inputName, inputValue
        );
        log(message);
        storeEvent(new PetriNetEvent("JOIN_INPUT_RECEIVED", placeId, sequenceId, message));
    }
    
    /**
     * Log join completed
     */
    public void logJoinCompleted(String placeId, String sequenceId, int expectedInputs, 
                                 int receivedInputs, boolean complete) {
        String message = String.format(
            "JOIN_COMPLETED: placeId=%s, sequenceId=%s, expected=%d, received=%d, complete=%b",
            placeId, sequenceId, expectedInputs, receivedInputs, complete
        );
        log(message);
        storeEvent(new PetriNetEvent("JOIN_COMPLETED", placeId, sequenceId, message));
    }
    
    // ========== Helper Methods ==========
    
    private void log(String message) {
        if (enableLogging) {
            logger.info(message);
        }
    }
    
    private void logWarn(String message) {
        if (enableLogging) {
            logger.warn(message);
        }
    }
    
    private void logError(String message, Exception e) {
        if (enableLogging) {
            logger.error(message, e);
        }
    }
    
    private void storeEvent(PetriNetEvent event) {
        if (enableEventStorage) {
            eventHistory.add(event);
        }
    }
    
    // ========== Query Methods ==========
    
    /**
     * Get all events
     */
    public List<PetriNetEvent> getEventHistory() {
        return new ArrayList<>(eventHistory);
    }
    
    /**
     * Get events for a specific place
     */
    public List<PetriNetEvent> getEventsForPlace(String placeId) {
        List<PetriNetEvent> placeEvents = new ArrayList<>();
        for (PetriNetEvent event : eventHistory) {
            if (event.getPlaceId().equals(placeId)) {
                placeEvents.add(event);
            }
        }
        return placeEvents;
    }
    
    /**
     * Get current place state
     */
    public PlaceState getPlaceState(String placeId) {
        return placeStates.get(placeId);
    }
    
    /**
     * Get all place states
     */
    public ConcurrentHashMap<String, PlaceState> getAllPlaceStates() {
        return new ConcurrentHashMap<>(placeStates);
    }
    
    /**
     * Clear event history
     */
    public void clearEventHistory() {
        eventHistory.clear();
    }
    
    /**
     * Clear place states
     */
    public void clearPlaceStates() {
        placeStates.clear();
    }
    
    /**
     * Enable/disable event storage
     */
    public void setEnableEventStorage(boolean enable) {
        this.enableEventStorage = enable;
    }
    
    /**
     * Enable/disable logging
     */
    public void setEnableLogging(boolean enable) {
        this.enableLogging = enable;
    }
    
    // ========== Inner Classes ==========
    
    /**
     * Petri Net Event
     */
    public static class PetriNetEvent {
        private final String eventType;
        private final String placeId;
        private final String sequenceId;
        private final String message;
        private final long timestamp;
        
        public PetriNetEvent(String eventType, String placeId, String sequenceId, String message) {
            this.eventType = eventType;
            this.placeId = placeId;
            this.sequenceId = sequenceId;
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }
        
        public String getEventType() { return eventType; }
        public String getPlaceId() { return placeId; }
        public String getSequenceId() { return sequenceId; }
        public String getMessage() { return message; }
        public long getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("[%d] %s: %s", timestamp, eventType, message);
        }
    }
    
    /**
     * Place State
     */
    public static class PlaceState {
        private final String placeId;
        private final int capacity;
        private int marking;
        private long lastUpdate;
        
        public PlaceState(String placeId, int capacity) {
            this.placeId = placeId;
            this.capacity = capacity;
            this.marking = 0;
            this.lastUpdate = System.currentTimeMillis();
        }
        
        public void setMarking(int marking) {
            this.marking = marking;
            this.lastUpdate = System.currentTimeMillis();
        }
        
        public String getPlaceId() { return placeId; }
        public int getCapacity() { return capacity; }
        public int getMarking() { return marking; }
        public long getLastUpdate() { return lastUpdate; }
        
        public boolean isAtCapacity() { return marking >= capacity; }
        public boolean isEmpty() { return marking == 0; }
        public double getUtilization() { return (double) marking / capacity * 100.0; }
        
        @Override
        public String toString() {
            return String.format("Place[%s]: M=%d, capacity=%d, util=%.1f%%", 
                               placeId, marking, capacity, getUtilization());
        }
    }
}