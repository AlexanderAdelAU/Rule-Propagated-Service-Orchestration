package org.btsn.business.credit;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * The service written in ServiceTutorial.md: approves a credit application up to a limit and refers the rest.
 *
 * Stateless: every call depends only on its input. The amount comes from the application's "amount" field
 * when the token carries one; otherwise application n asks for n x 1,000, so a run of ten applications
 * gives five of each route.
 *
 * Routes: "approve" (within the limit) or "refer" (over it).
 */
public class CreditLimitService extends BaseBusinessService {

    static final double LIMIT = 5000;

    private final String sequenceId;

    public CreditLimitService() { this(""); }

    /** The host passes the token's sequence id. */
    public CreditLimitService(String sequenceId) { this.sequenceId = sequenceId == null ? "" : sequenceId; }

    /** token -> creditDecision */
    @SuppressWarnings("unchecked")
    public String assess(String token) {
        int application = applicationNumber(sequenceId);
        double amount = numberValue(token, "amount", application * 1000.0);
        boolean approve = amount <= LIMIT;

        JSONObject result = new JSONObject();
        result.put("application", "APP-" + application);
        result.put("amount", amount);
        result.put("limit", LIMIT);
        result.put("routing_decision", routingDecision(approve ? "approve" : "refer"));
        return businessResult("creditDecision", result);
    }

    /** 1 for the first token of a version, 2 for the second, ... (token ids are base + n x 10000). */
    static int applicationNumber(String sequenceId) {
        try {
            return (int)((Long.parseLong(sequenceId.trim()) % 1_000_000L) / 10_000L) + 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
