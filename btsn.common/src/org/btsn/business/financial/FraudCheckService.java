package org.btsn.business.financial;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Logical Financial FraudCheckService business implementation.
 */
public class FraudCheckService extends BaseBusinessService {

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
