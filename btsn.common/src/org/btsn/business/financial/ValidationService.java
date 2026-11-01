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
        // The application's workflow number and the end of its lifetime travel with it, so a later service can record
        // what it did against the application and a clean-up process can tell when it is safe to undo that.
        String workflowId = textValue(token, "tokenId", null);
        String notAfter = textValue(token, "notAfter", null);
        if (workflowId != null) result.put("workflow_id", workflowId);
        if (notAfter != null) result.put("not_after", notAfter);
        // Optional simulation setting for the compensation demonstration (see FraudCheckService).
        String fraudOutage = textValue(token, "fraud_outage", null);
        if (fraudOutage != null) result.put("fraud_outage", fraudOutage);

        return businessResult("validationResults", result);
    }
}
