package com.editor.animator;

import java.util.List;
import java.util.Map;

/**
 * Animation snapshot at a point in time.
 * Extracted from TokenAnimator - NO LOGIC CHANGES.
 */
public class AnimationSnapshot {
    public final Map<String, TokenAnimState> tokenStates;
    public final Map<String, List<BufferedToken>> bufferStates;
    
    public AnimationSnapshot(Map<String, TokenAnimState> tokenStates,
                            Map<String, List<BufferedToken>> bufferStates) {
        this.tokenStates = tokenStates;
        this.bufferStates = bufferStates;
    }
}
