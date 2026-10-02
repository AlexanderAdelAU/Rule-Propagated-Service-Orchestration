package org.btsn.places;

import org.btsn.base.BaseBusinessPetriNetPlace;
import org.json.simple.JSONObject;

/**
 * P5 - FinancialSystem Decision role.
 */
public class P5_Place extends BaseBusinessPetriNetPlace {

    private static final String PLACE_IDENTIFIER = "P5";

    public P5_Place(String sequenceID) {
        super(sequenceID, PLACE_IDENTIFIER);
    }

    /**
     * underwritingResults -> decisionResults
     */
    @SuppressWarnings("unchecked")
    public String processToken(String underwritingResults) {
        String applicationId = textValue(underwritingResults, "application_id", "APP-UNKNOWN");
        String decision = textValue(underwritingResults, "underwriting_decision", "conditional");

        JSONObject result = new JSONObject();
        result.put("application_id", applicationId);
        result.put("final_decision", decision);
        result.put("status", "complete");

        return businessResult("decisionResults", result);
    }
}
