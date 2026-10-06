package org.btsn.business.financial;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Logical Financial DecisionService business implementation.
 */
public class DecisionService extends BaseBusinessService {

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
