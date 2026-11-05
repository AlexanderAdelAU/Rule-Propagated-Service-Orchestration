package org.btsn.business.healthcare;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The stroke centre's angiography suite bookings: the side effect a stroke alert leaves behind. Kept by whatever runs
 * AngioSuiteService, for its lifetime: the stroke centre's own booking system when the service is served over REST
 * (RestServiceServer), or the orchestration host when it runs in-process. Both AngioSuiteService.holdSuite, which holds
 * the suite for a stroke code, and AngioSuiteService.releaseSuite, which releases it when the code no longer needs it,
 * run there.
 */
public final class SuiteHolds {
    /** The outcome of a request to release a hold. */
    public enum Release { RELEASED, ALREADY_RELEASED, NO_HOLD }

    private static final class Hold {
        boolean released;
    }

    private static final Map<String, Hold> HOLDS = new ConcurrentHashMap<>();

    private SuiteHolds() { }

    /**
     * Holds the suite for the stroke code. A repeated alert for a code still held keeps that hold; a code whose hold
     * was released is held again (a later run of the same numbers, when the booking system outlives the run).
     */
    public static synchronized void hold(String workflowId) {
        Hold existing = HOLDS.get(workflowId);
        if (existing == null || existing.released) HOLDS.put(workflowId, new Hold());
    }

    /** The stroke codes holding the suite now. */
    public static long held() {
        return HOLDS.values().stream().filter(h -> !h.released).count();
    }

    /**
     * Releases the code's hold, once. Asked by the stand-down process, which the stroke code signals only once it has
     * decided the patient will not need clot retrieval, so nothing will still use the hold.
     */
    public static synchronized Release release(String workflowId) {
        Hold hold = HOLDS.get(workflowId);
        if (hold == null) return Release.NO_HOLD;
        if (hold.released) return Release.ALREADY_RELEASED;
        hold.released = true;
        return Release.RELEASED;
    }

    /** For checks: forget every hold. */
    static void clear() { HOLDS.clear(); }
}
