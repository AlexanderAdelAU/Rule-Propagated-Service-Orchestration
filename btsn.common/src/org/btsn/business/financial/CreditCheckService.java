package org.btsn.business.financial;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Logical Financial CreditCheckService business implementation.
 */
public class CreditCheckService extends BaseBusinessService {

    /**
     * validationResults -> creditCheckResults
     *
     * Records a credit enquiry against the application (CreditEnquiries) when its workflow number is known.
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
        result.put("routing_decision", routingDecision("loan"));

        String workflowId = textValue(validationResults, "workflow_id", null);
        if (workflowId != null) {
            CreditEnquiries.record(workflowId);
            result.put("workflow_id", workflowId);
            result.put("enquiry_recorded", true);
        }

        return businessResult("creditCheckResults", result);
    }

    /**
     * token -> withdrawalResults
     *
     * Compensation for an application the loan process abandoned after its credit check: withdraws the enquiry the
     * check recorded. The token was launched by the loan process's Signal node; its signal header names the
     * abandoned application's workflow (source_workflow). Safe to repeat. Routes on "withdrawn" or
     * "nothing_to_withdraw" (no enquiry, or already withdrawn).
     */
    @SuppressWarnings("unchecked")
    public String withdrawEnquiry(String token) {
        String workflowId = textValue(token, "source_workflow", "UNKNOWN");
        CreditEnquiries.Withdrawal outcome = CreditEnquiries.withdraw(workflowId);
        String route = outcome == CreditEnquiries.Withdrawal.WITHDRAWN ? "withdrawn" : "nothing_to_withdraw";

        JSONObject result = new JSONObject();
        result.put("loan_workflow", workflowId);
        result.put("withdrawal", outcome.name().toLowerCase());
        result.put("routing_decision", routingDecision(route));

        return businessResult("withdrawalResults", result);
    }
}
