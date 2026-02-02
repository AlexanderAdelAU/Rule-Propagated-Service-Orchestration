package com.editor.animator;

/**
 * Current animation state of a single token (for Canvas rendering).
 * Extracted from TokenAnimator - NO LOGIC CHANGES.
 */
public class TokenAnimState {
    public String tokenId;
    public String version;
    public Phase phase;
    public String currentPlaceId;
    public String eventGenId;
    public String tInId;
    public String tOutId;
    public String nextTInId;
    public String terminateNodeId;
    public String fromElementId;   // Element traveling FROM (for travel phases)
    public String toElementId;     // Element traveling TO (for travel phases)
    public long phaseStartTime;
    public long phaseEndTime;
    public double progress;
    public int bufferPosition;
    
    public TokenAnimState(String tokenId, String version) {
        this.tokenId = tokenId;
        this.version = version;
        this.phase = Phase.AT_EVENT_GENERATOR;
        this.progress = 0.0;
        this.bufferPosition = 0;
    }
}
