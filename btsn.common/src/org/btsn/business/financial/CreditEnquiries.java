package org.btsn.business.financial;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The credit bureau's record of enquiries made against applications: the side effect a credit check leaves behind.
 * Kept by the host that runs CreditCheckService, for that host's lifetime: both CreditCheckService.processToken, which
 * records an enquiry, and CreditCheckService.withdrawEnquiry, which withdraws it, run there.
 */
public final class CreditEnquiries {
    /** The outcome of a request to withdraw an enquiry. */
    public enum Withdrawal { WITHDRAWN, ALREADY_WITHDRAWN, NO_ENQUIRY, TOO_EARLY }

    private static final class Enquiry {
        final long notAfter;
        boolean withdrawn;
        Enquiry(long notAfter) { this.notAfter = notAfter; }
    }

    private static final Map<String, Enquiry> ENQUIRIES = new ConcurrentHashMap<>();

    private CreditEnquiries() { }

    /** Records an enquiry for the application; a repeated check of the same application keeps the first. */
    public static void record(String workflowId, long notAfter) {
        ENQUIRIES.putIfAbsent(workflowId, new Enquiry(notAfter));
    }

    /**
     * Withdraws the application's enquiry, once. Refused before the application's lifetime has ended (notAfter plus
     * the margin): until then its other branches may still arrive and complete it normally.
     */
    public static synchronized Withdrawal withdraw(String workflowId, long now, long marginMs) {
        Enquiry enquiry = ENQUIRIES.get(workflowId);
        if (enquiry == null) return Withdrawal.NO_ENQUIRY;
        if (enquiry.withdrawn) return Withdrawal.ALREADY_WITHDRAWN;
        if (now < enquiry.notAfter + marginMs) return Withdrawal.TOO_EARLY;
        enquiry.withdrawn = true;
        return Withdrawal.WITHDRAWN;
    }

    /** For checks: forget every enquiry. */
    static void clear() { ENQUIRIES.clear(); }
}
