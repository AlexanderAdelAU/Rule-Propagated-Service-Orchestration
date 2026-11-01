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
     * Records the application as settled (SettledApplications) when its workflow number is known.
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

        String workflowId = textValue(creditCheckResults, "workflow_id", null);
        if (workflowId != null) {
            SettledApplications.record(workflowId);
            result.put("workflow_id", workflowId);
        }

        return businessResult("underwritingResults", result);
    }

    /**
     * token -> settlementCheck
     *
     * The first step of the loan compensation process: was this application settled by underwriting? The token is
     * one of the compensation process's own; it audits the loan application with the same number in the version
     * named by its audited_version data (token 2030000 of v002 audits application 1030000 of v001). Routes on
     * "settled" (nothing to undo) or "abandoned" (never reached underwriting: undo what its checks left behind).
     */
    @SuppressWarnings("unchecked")
    public String checkSettled(String token) {
        String loanWorkflow = auditedWorkflow(textValue(token, "tokenId", "0"), textValue(token, "audited_version", "v001"));
        boolean settled = SettledApplications.settled(loanWorkflow);

        JSONObject result = new JSONObject();
        result.put("loan_workflow", loanWorkflow);
        result.put("settled", settled);
        result.put("routing_decision", routingDecision(settled ? "settled" : "abandoned"));

        return businessResult("settlementCheck", result);
    }

    /**
     * The audited application's workflow number: the audit token's number within its version, in the audited version;
     * "UNKNOWN" for a token or version without a number (such an audit settles nothing and withdraws nothing).
     */
    static String auditedWorkflow(String auditTokenId, String auditedVersion) {
        try {
            long version = Long.parseLong(auditedVersion.replaceAll("\\D", ""));
            long tokenId = Long.parseLong(auditTokenId);
            long workflow = tokenId % 1_000_000 / 10_000 * 10_000;   // the root of the audit token's workflow
            return Long.toString(version * 1_000_000 + workflow);
        } catch (NumberFormatException e) {
            return "UNKNOWN";
        }
    }
}
