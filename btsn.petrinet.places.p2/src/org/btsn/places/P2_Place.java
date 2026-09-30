package org.btsn.places;

import org.btsn.base.BaseBusinessPetriNetPlace;
import org.json.simple.JSONObject;

/**
 * P2 - Financial business capabilities.
 *
 * Workflow bindings select the business operation performed by this physical
 * service. The full-loan workflow uses processToken; the pre-screen workflow
 * uses assessAffordability.
 */
public class P2_Place extends BaseBusinessPetriNetPlace {

    private static final String PLACE_IDENTIFIER = "P2";

    public P2_Place(String sequenceID) {
        super(sequenceID, PLACE_IDENTIFIER);
    }

    /**
     * validationResults -> creditCheckResults
     */
    @SuppressWarnings("unchecked")
    public String processToken(String validationResults) {
        String applicationId = textValue(validationResults, "application_id", "APP-UNKNOWN");
        double creditScore = numberValue(validationResults, "credit_score", 0.0);
        double annualIncome = numberValue(validationResults, "annual_income", 0.0);
        double requestedAmount = numberValue(validationResults, "requested_amount", 0.0);

        String creditStatus = creditScore >= 700.0 ? "strong"
                : creditScore >= 620.0 ? "review"
                : "weak";

        JSONObject result = new JSONObject();
        result.put("application_id", applicationId);
        result.put("credit_score", creditScore);
        result.put("annual_income", annualIncome);
        result.put("requested_amount", requestedAmount);
        result.put("credit_status", creditStatus);

        return businessResult("creditCheckResults", result);
    }
    /**
     * identityVerificationResults -> affordabilityAssessmentResults
     *
     * Used by the independent Financial Pre-Screen workflow. The assessment is
     * intentionally deterministic so Stage-3 concurrency measurements are not
     * confounded by random business outcomes.
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

        return businessResult("affordabilityAssessmentResults", result);
    }

}
