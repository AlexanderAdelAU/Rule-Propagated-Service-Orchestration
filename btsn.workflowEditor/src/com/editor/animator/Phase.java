package com.editor.animator;

/**
 * Token animation phase.
 * Extracted from TokenAnimator - NO LOGIC CHANGES.
 */
public enum Phase {
    AT_EVENT_GENERATOR,
    TRAVELING_TO_TIN,
    BUFFERED_AT_TIN,
    TRAVELING_TO_PLACE,
    AT_PLACE,
    TRAVELING_TO_TOUT,
    TRAVELING_TO_NEXT_TIN,
    TRAVELING_TO_TERMINATE,
    AT_TERMINATE,
    CONSUMED
}
