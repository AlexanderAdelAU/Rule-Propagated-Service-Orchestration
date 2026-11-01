package org.btsn.business.financial;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The applications that reached underwriting, where both checks were joined and the application was settled (approved,
 * conditional or declined). Kept by the host that runs UnderwritingService, for that host's lifetime: both
 * UnderwritingService.processToken, which records a settlement, and UnderwritingService.checkSettled, which asks
 * for one, run there.
 */
public final class SettledApplications {
    private static final Set<String> SETTLED = ConcurrentHashMap.newKeySet();

    private SettledApplications() { }

    public static void record(String workflowId) { SETTLED.add(workflowId); }

    public static boolean settled(String workflowId) { return SETTLED.contains(workflowId); }

    /** For checks: forget every settlement. */
    static void clear() { SETTLED.clear(); }
}
