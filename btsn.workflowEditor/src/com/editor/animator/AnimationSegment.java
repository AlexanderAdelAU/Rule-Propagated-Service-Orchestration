package com.editor.animator;

/**
 * Pre-computed animation segment.
 * Represents a token in a specific state for a specific time range.
 * Extracted from TokenAnimator - NO LOGIC CHANGES.
 */
public class AnimationSegment {
    public final String tokenId;
    public final String version;
    public final Phase phase;
    public final long startTime;
    public final long endTime;
    
    // Location information (meaning depends on phase)
    public final String placeId;      // Place service name
    public final String tInId;        // T_in transition label
    public final String tOutId;       // T_out transition label
    public final String fromElement;  // Element traveling FROM
    public final String toElement;    // Element traveling TO
    public final String terminateId;  // Terminate node (if applicable)
    public final String eventGenId;   // Event generator (if applicable)
    
    public AnimationSegment(String tokenId, String version, Phase phase,
                           long startTime, long endTime,
                           String placeId, String tInId, String tOutId,
                           String fromElement, String toElement,
                           String terminateId, String eventGenId) {
        this.tokenId = tokenId;
        this.version = version;
        this.phase = phase;
        this.startTime = startTime;
        this.endTime = endTime;
        this.placeId = placeId;
        this.tInId = tInId;
        this.tOutId = tOutId;
        this.fromElement = fromElement;
        this.toElement = toElement;
        this.terminateId = terminateId;
        this.eventGenId = eventGenId;
    }
    
    /**
     * Calculate progress (0.0 to 1.0) within this segment at given time
     */
    public double getProgress(long time) {
        if (endTime <= startTime) return 1.0;
        double progress = (double)(time - startTime) / (endTime - startTime);
        return Math.max(0.0, Math.min(1.0, progress));
    }
    
    /**
     * Check if this segment is active at given time
     */
    public boolean isActiveAt(long time) {
        return time >= startTime && time < endTime;
    }
    
    @Override
    public String toString() {
        return String.format("Segment[%s %s %d-%d %s->%s place=%s]",
            tokenId, phase, startTime, endTime, fromElement, toElement, placeId);
    }
}
