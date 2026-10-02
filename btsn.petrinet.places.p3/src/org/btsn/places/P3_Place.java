package org.btsn.places;

import org.btsn.base.BaseBusinessPetriNetPlace;
import org.json.simple.JSONObject;

/**
 * P3 - FinancialSystem FraudCheck role.
 */
public class P3_Place extends BaseBusinessPetriNetPlace {

    private static final String PLACE_IDENTIFIER = "P3";

    public P3_Place(String sequenceID) {
        super(sequenceID, PLACE_IDENTIFIER);
    }

    /**
     * validationResults -> fraudCheckResults
     */
    @SuppressWarnings("unchecked")
    public String processToken(String validationResults) {
        String applicationId = textValue(validationResults, "application_id", "APP-UNKNOWN");
        String fraudRisk = textValue(validationResults, "fraud_risk", "unknown");
        String fraudStatus = "high".equalsIgnoreCase(fraudRisk) ? "flagged" : "clear";

        JSONObject result = new JSONObject();
        result.put("application_id", applicationId);
        result.put("fraud_risk", fraudRisk);
        result.put("fraud_status", fraudStatus);

        return businessResult("fraudCheckResults", result);
    }
}
