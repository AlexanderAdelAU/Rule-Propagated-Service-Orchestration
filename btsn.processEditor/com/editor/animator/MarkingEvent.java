package com.editor.animator;

/**
 * Represents a marking event from the Petri net analysis data.
 * 
 * Events are parsed from analyzer output and used to generate animation segments.
 * Each event represents a token entering, exiting, or changing state at a place.
 */
public class MarkingEvent implements Comparable<MarkingEvent> {
    
    public final long timestamp;
    public final String tokenId;
    public final String version;
    public final String placeId;
    public final boolean entering;
    public final int buffer;
    public final String toPlace;
    public final String eventType;
    public final String transitionId;
    
    public MarkingEvent(long timestamp, String tokenId, String version,
                       String placeId, boolean entering, int buffer,
                       String toPlace, String eventType, String transitionId) {
        this.timestamp = timestamp;
        this.tokenId = tokenId;
        this.version = version;
        this.placeId = placeId;
        this.entering = entering;
        this.buffer = buffer;
        this.toPlace = toPlace;
        this.eventType = eventType;
        this.transitionId = transitionId;
    }
    
    // Convenience constructor without transitionId
    public MarkingEvent(long timestamp, String tokenId, String version,
                       String placeId, boolean entering, int buffer,
                       String toPlace, String eventType) {
        this(timestamp, tokenId, version, placeId, entering, buffer, toPlace, eventType, null);
    }
    
    // Event type queries
    public boolean isFork() { return "FORK".equals(eventType); }
    public boolean isEnter() { return "ENTER".equals(eventType) || (entering && eventType == null); }
    public boolean isExit() { return "EXIT".equals(eventType) || (!entering && eventType == null); }
    public boolean isBuffered() { return "BUFFERED".equals(eventType); }
    public boolean isJoinConsumed() { return "JOIN_CONSUMED".equals(eventType); }
    public boolean isJoinSurvivor() { return "JOIN_SURVIVOR".equals(eventType); }
    public boolean isTerminate() { return "TERMINATE".equals(eventType); }
    public boolean isForkConsumed() { return "FORK_CONSUMED".equals(eventType); }
    public boolean isGenerated() { return "GENERATED".equals(eventType); }
    
    @Override
    public int compareTo(MarkingEvent other) {
        int cmp = Long.compare(this.timestamp, other.timestamp);
        if (cmp != 0) return cmp;
        // Secondary: exits before enters at same timestamp
        if (this.entering != other.entering) {
            return this.entering ? 1 : -1;
        }
        return 0;
    }
    
    @Override
    public String toString() {
        return String.format("Event[t=%d %s %s %s->%s type=%s]",
            timestamp, tokenId, placeId, entering ? "ENTER" : "EXIT", 
            toPlace, eventType);
    }
}
