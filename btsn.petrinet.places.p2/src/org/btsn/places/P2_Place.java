package org.btsn.places;

import org.btsn.base.BaseBusinessPetriNetPlace;
import org.json.simple.JSONObject;

/**
 * P2 - FinancialSystem CreditCheck role.
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
}
