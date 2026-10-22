package org.btsn.business.claims;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Sorts an insurance claim: a small claim is paid straight away, a larger one is assessed.
 *
 * The amount comes from the claim's "amount" field when the token carries one; otherwise claim n is for
 * n x 500, so a run of ten claims gives five of each route.
 *
 * Returns: The claim, its amount and the threshold. Routes on "simple" (paid) or "complex" (assessed).
 */
public class ClaimsTriageService extends BaseBusinessService {

    static final double THRESHOLD = 2500;

    private final String sequenceId;

    public ClaimsTriageService() { this(""); }

    /** The host passes the token's sequence id. */
    public ClaimsTriageService(String sequenceId) { this.sequenceId = sequenceId == null ? "" : sequenceId; }

    /** token -> token */
    @SuppressWarnings("unchecked")
    public String triage(String token) {
        int claim = claimNumber(sequenceId);
        double amount = numberValue(token, "amount", claim * 500.0);

        JSONObject result = new JSONObject();
        result.put("claim", "CLM-" + claim);
        result.put("amount", amount);
        result.put("threshold", THRESHOLD);
        result.put("routing_decision", routingDecision(amount <= THRESHOLD ? "simple" : "complex"));
        return businessResult("token", result);
    }

    /** 1 for the first token of a version, 2 for the second, ... (token ids are base + n x 10000). */
    static int claimNumber(String sequenceId) {
        try {
            return (int)((Long.parseLong(sequenceId.trim()) % 1_000_000L) / 10_000L) + 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
