package com.editor.animator;

/**
 * Token animation phases.
 * 
 * Represents the discrete states a token can be in during animation.
 * The animation system pre-computes segments for each phase, then
 * interpolates position based on progress within the phase.
 */
public enum Phase {
    /** Token visible at event generator before entering workflow */
    AT_EVENT_GENERATOR,
    
    /** Token traveling from event generator to T_in transition */
    TRAVELING_TO_TIN,
    
    /** Token waiting in buffer at T_in (e.g., waiting for JOIN) */
    BUFFERED_AT_TIN,
    
    /** Token traveling from T_in into the place */
    TRAVELING_TO_PLACE,
    
    /** Token inside a place (being processed) */
    AT_PLACE,
    
    /** Token traveling from place to T_out transition */
    TRAVELING_TO_TOUT,
    
    /** Token traveling from T_out to next T_in */
    TRAVELING_TO_NEXT_TIN,
    
    /** Token traveling to terminate node */
    TRAVELING_TO_TERMINATE,
    
    /** Token at terminate node (workflow complete) */
    AT_TERMINATE,
    
    /** Token consumed (invisible) - e.g., JOIN consumed or parent at FORK */
    CONSUMED
}
