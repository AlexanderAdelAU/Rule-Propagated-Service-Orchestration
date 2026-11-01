package org.btsn.business.financial;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Logical Financial UnderwritingService business implementation.
 *
 * Join synchronization is an orchestration concern. This service receives the
 * two named business inputs only after the workflow has made them available.
 */
public class UnderwritingService extends BaseBusinessService {

    /**
     * creditCheckResults + fraudCheckResults -> underwritingResults
     *
     * When the fraud check could not be done (fraud_status "unavailable"), no lending decision can be made: the
     * application is abandoned, and the loan process signals for the work already done for it to be undone.
     */
    @SuppressWarnings("unchecked")
    public String processToken(String creditCheckResults, String fraudCheckResults) {
        String applicationId = textValue(creditCheckResults, "application_id", "APP-UNKNOWN");
        double creditScore = numberValue(creditCheckResults, "credit_score", 0.0);
        double annualIncome = numberValue(creditCheckResults, "annual_income", 0.0);
        double requestedAmount = numberValue(creditCheckResults, "requested_amount", 0.0);
        String fraudRisk = textValue(fraudCheckResults, "fraud_risk", "unknown");

        String decision;
        if ("unavailable".equalsIgnoreCase(textValue(fraudCheckResults, "fraud_status", ""))) {
            decision = "abandoned";
        } else if ("high".equalsIgnoreCase(fraudRisk) || creditScore < 600.0) {
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

        String workflowId = textValue(creditCheckResults, "workflow_id", null);
        if (workflowId != null) result.put("workflow_id", workflowId);

        return businessResult("underwritingResults", result);
    }
}
