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
    public enum Withdrawal { WITHDRAWN, ALREADY_WITHDRAWN, NO_ENQUIRY }

    private static final class Enquiry {
        boolean withdrawn;
    }

    private static final Map<String, Enquiry> ENQUIRIES = new ConcurrentHashMap<>();

    private CreditEnquiries() { }

    /** Records an enquiry for the application; a repeated check of the same application keeps the first. */
    public static void record(String workflowId) {
        ENQUIRIES.putIfAbsent(workflowId, new Enquiry());
    }

    /**
     * Withdraws the application's enquiry, once. Asked by the loan compensation process, which the loan process
     * signals only once it has abandoned the application, so nothing else will still complete it.
     */
    public static synchronized Withdrawal withdraw(String workflowId) {
        Enquiry enquiry = ENQUIRIES.get(workflowId);
        if (enquiry == null) return Withdrawal.NO_ENQUIRY;
        if (enquiry.withdrawn) return Withdrawal.ALREADY_WITHDRAWN;
        enquiry.withdrawn = true;
        return Withdrawal.WITHDRAWN;
    }

    /** For checks: forget every enquiry. */
    static void clear() { ENQUIRIES.clear(); }
}
