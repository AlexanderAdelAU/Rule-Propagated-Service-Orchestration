package com.editor.animator;

/**
 * Buffered token for display.
 * Extracted from TokenAnimator - NO LOGIC CHANGES.
 */
public class BufferedToken {
    public final String tokenId;
    public final String version;
    public final long arrivalTime;
    
    public BufferedToken(String tokenId, String version, long arrivalTime) {
        this.tokenId = tokenId;
        this.version = version;
        this.arrivalTime = arrivalTime;
    }
}
