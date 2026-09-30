package org.btsn.places;

import org.btsn.base.BaseBusinessPetriNetPlace;
import org.json.simple.JSONObject;

/**
 * P4 - FinancialSystem Underwriting role.
 *
 * The orchestration layer performs the join. P4 simply receives the two named
 * business objects once that join has completed.
 */
public class P4_Place extends BaseBusinessPetriNetPlace {

    private static final String PLACE_IDENTIFIER = "P4";

    public P4_Place(String sequenceID) {
        super(sequenceID, PLACE_IDENTIFIER);
    }

    /**
     * creditCheckResults + fraudCheckResults -> underwritingResults
     */
    @SuppressWarnings("unchecked")
    public String processToken(String creditCheckResults, String fraudCheckResults) {
        String applicationId = textValue(creditCheckResults, "application_id", "APP-UNKNOWN");
        double creditScore = numberValue(creditCheckResults, "credit_score", 0.0);
        double annualIncome = numberValue(creditCheckResults, "annual_income", 0.0);
        double requestedAmount = numberValue(creditCheckResults, "requested_amount", 0.0);
        String fraudRisk = textValue(fraudCheckResults, "fraud_risk", "unknown");

        String decision;
        if ("high".equalsIgnoreCase(fraudRisk) || creditScore < 600.0) {
            decision = "declined";
        } else if (creditScore >= 700.0 && "low".equalsIgnoreCase(fraudRisk)) {
            decision = "approved";
        } else {
            decision = "conditional";
        }

        JSONObject result = new JSONObject();
        result.put("application_id", applicationId);
        result.put("credit_score", creditScore);
        result.put("annual_income", annualIncome);
        result.put("requested_amount", requestedAmount);
        result.put("fraud_risk", fraudRisk);
        result.put("underwriting_decision", decision);
        result.put("routing_decision", routingDecision(decision));

        return businessResult("underwritingResults", result);
    }
}
