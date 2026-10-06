package org.btsn.business.financial;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Preserved Financial identity-verification capability.
 *
 * This capability is not used by the current Stage-5 workflow but is retained
 * as an identifiable business implementation rather than being buried in P1.
 */
public class IdentityVerificationService extends BaseBusinessService {

    /**
     * token -> identityVerificationResults
     */
    @SuppressWarnings("unchecked")
    public String verifyApplicantIdentity(String token) {
        String applicationId = textValue(token, "application_id", "APP-UNKNOWN");
        double annualIncome = numberValue(token, "annual_income", 0.0);
        double requestedAmount = numberValue(token, "requested_amount", 0.0);
        double creditScore = numberValue(token, "credit_score", 0.0);
        String fraudRisk = textValue(token, "fraud_risk", "unknown");

        String identityStatus = "APP-UNKNOWN".equals(applicationId) ? "unverified" : "verified";

        JSONObject result = new JSONObject();
        result.put("application_id", applicationId);
        result.put("annual_income", annualIncome);
        result.put("requested_amount", requestedAmount);
        result.put("credit_score", creditScore);
        result.put("fraud_risk", fraudRisk);
        result.put("identity_status", identityStatus);
        result.put("routing_decision", routingDecision(
                "verified".equalsIgnoreCase(identityStatus) ? "prescreen" : "unverified"));

        return businessResult("identityVerificationResults", result);
    }
}
