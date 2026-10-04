package org.btsn.business.financial;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Logical Financial ValidationService business implementation.
 *
 * Physical placement is supplied independently by the Infrastructure Definition.
 */
public class ValidationService extends BaseBusinessService {

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
