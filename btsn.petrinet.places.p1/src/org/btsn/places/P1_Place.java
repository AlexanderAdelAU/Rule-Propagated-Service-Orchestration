package org.btsn.places;

import org.btsn.base.BaseBusinessPetriNetPlace;
import org.json.simple.JSONObject;

/**
 * P1 - FinancialSystem Validation role.
 *
 * Petri-net routing, buffering and timing are handled outside this service.
 * This class performs only the business operation assigned to P1.
 */
public class P1_Place extends BaseBusinessPetriNetPlace {

    private static final String PLACE_IDENTIFIER = "P1";

    public P1_Place(String sequenceID) {
        super(sequenceID, PLACE_IDENTIFIER);
    }

    /**
     * token -> validationResults
     */
    @SuppressWarnings("unchecked")
    public String processToken(String token) {
        String applicationId = textValue(token, "application_id", "APP-UNKNOWN");
        double annualIncome = numberValue(token, "annual_income", 0.0);
        double requestedAmount = numberValue(token, "requested_amount", 0.0);
        double creditScore = numberValue(token, "credit_score", 0.0);
        String fraudRisk = textValue(token, "fraud_risk", "unknown");

        boolean valid = !"APP-UNKNOWN".equals(applicationId)
                && annualIncome > 0.0
                && requestedAmount > 0.0;

        String route = valid ? "valid" : "invalid";

        JSONObject result = new JSONObject();
        result.put("application_id", applicationId);
        result.put("annual_income", annualIncome);
        result.put("requested_amount", requestedAmount);
        result.put("credit_score", creditScore);
        result.put("fraud_risk", fraudRisk);
        result.put("validation_status", route);
        result.put("routing_decision", routingDecision(route));

        return businessResult("validationResults", result);
    }
}
