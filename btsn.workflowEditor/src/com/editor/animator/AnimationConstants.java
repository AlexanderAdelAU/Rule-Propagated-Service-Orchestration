package com.editor.animator;

/**
 * Animation timing constants.
 * Extracted from TokenAnimator - NO LOGIC CHANGES.
 * 
 * VELOCITY-BASED TIMING:
 * Gives consistent visual speed regardless of path length.
 * Travel duration = distance / VELOCITY
 */
public final class AnimationConstants {
    
    private AnimationConstants() {} // Prevent instantiation
    
    // ==================== Core Timing ====================
    
    /** Pixels per millisecond - adjust for desired animation speed */
    public static final double VELOCITY = 0.5;
    
    /** Minimum visible travel time (ms) */
    public static final long MIN_TRAVEL_DURATION = 50;
    
    /** Time spent visually at event generator (ms) */
    public static final long TIME_AT_EVENT_GEN = 300;
    
    // ==================== Fallback Durations ====================
    // Used when element positions unavailable (backwards compatibility)
    
    /** Event Generator → T_in travel time */
    public static final long FALLBACK_TRAVEL_DURATION_EG_TO_TIN = 150;
    
    /** T_in → Place travel time */
    public static final long FALLBACK_TRAVEL_DURATION_TO_PLACE = 120;
    
    /** Place → T_out travel time */
    public static final long FALLBACK_TRAVEL_DURATION_TO_TOUT = 80;
    
    /** T_out → next T_in travel time */
    public static final long FALLBACK_TRAVEL_DURATION_TO_NEXT = 300;
    
    // ==================== Aliases ====================
    // Backwards-compatible names (used where distance calculation not yet implemented)
    
    public static final long TRAVEL_DURATION_EG_TO_TIN = FALLBACK_TRAVEL_DURATION_EG_TO_TIN;
    public static final long TRAVEL_DURATION_TO_PLACE = FALLBACK_TRAVEL_DURATION_TO_PLACE;
    public static final long TRAVEL_DURATION_TO_TOUT = FALLBACK_TRAVEL_DURATION_TO_TOUT;
    public static final long TRAVEL_DURATION_TO_NEXT = FALLBACK_TRAVEL_DURATION_TO_NEXT;
}
