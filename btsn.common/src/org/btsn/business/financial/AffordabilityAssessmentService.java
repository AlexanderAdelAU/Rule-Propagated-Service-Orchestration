package org.btsn.business.financial;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Preserved Financial affordability-assessment capability.
 *
 * This capability is not used by the current Stage-5 workflow but is retained
 * as an identifiable business implementation rather than being buried in P2.
 */
public class AffordabilityAssessmentService extends BaseBusinessService {

    /**
     * identityVerificationResults -> affordabilityAssessmentResults
     */
    @SuppressWarnings("unchecked")
    public String assessAffordability(String identityVerificationResults) {
        String applicationId = textValue(identityVerificationResults, "application_id", "APP-UNKNOWN");
        double annualIncome = numberValue(identityVerificationResults, "annual_income", 0.0);
        double requestedAmount = numberValue(identityVerificationResults, "requested_amount", 0.0);
        double creditScore = numberValue(identityVerificationResults, "credit_score", 0.0);
        String identityStatus = textValue(identityVerificationResults, "identity_status", "unverified");

        double amountToIncomeRatio = annualIncome > 0.0 ? requestedAmount / annualIncome : 1.0;
        String affordabilityStatus;
        if (!"verified".equalsIgnoreCase(identityStatus)) {
            affordabilityStatus = "identity_review";
        } else if (amountToIncomeRatio <= 0.35 && creditScore >= 620.0) {
            affordabilityStatus = "affordable";
        } else {
            affordabilityStatus = "review";
        }

        JSONObject result = new JSONObject();
        result.put("application_id", applicationId);
        result.put("annual_income", annualIncome);
        result.put("requested_amount", requestedAmount);
        result.put("credit_score", creditScore);
        result.put("amount_to_income_ratio", amountToIncomeRatio);
        result.put("affordability_status", affordabilityStatus);
        result.put("status", "complete");
        result.put("routing_decision", routingDecision("prescreen_complete"));

        return businessResult("affordabilityAssessmentResults", result);
    }
}
