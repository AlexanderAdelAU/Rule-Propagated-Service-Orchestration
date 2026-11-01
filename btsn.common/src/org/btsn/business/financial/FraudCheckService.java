package org.btsn.business.financial;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Logical Financial FraudCheckService business implementation.
 */
public class FraudCheckService extends BaseBusinessService {

    /**
     * validationResults -> fraudCheckResults
     *
     * For the compensation demonstration only: when the application carries fraud_outage=FROM-TO, the fraud check is
     * unavailable for applications FROM to TO (numbered from 1 in the order they were submitted) and reports
     * fraud_status "unavailable". Their credit check has already run; underwriting abandons them, and the loan process
     * signals the compensation process to withdraw their enquiry.
     */
    @SuppressWarnings("unchecked")
    public String processToken(String validationResults) {
        String applicationId = textValue(validationResults, "application_id", "APP-UNKNOWN");
        String fraudRisk = textValue(validationResults, "fraud_risk", "unknown");
        String fraudStatus = "high".equalsIgnoreCase(fraudRisk) ? "flagged" : "clear";

        String outage = textValue(validationResults, "fraud_outage", null);
        String workflowId = textValue(validationResults, "workflow_id", null);
        if (outage != null && workflowId != null && inOutage(outage, workflowId)) fraudStatus = "unavailable";

        JSONObject result = new JSONObject();
        result.put("application_id", applicationId);
        result.put("fraud_risk", fraudRisk);
        result.put("fraud_status", fraudStatus);

        return businessResult("fraudCheckResults", result);
    }

    /** An application's number in submission order, from 1: workflow 1000000 is 1, 1010000 is 2, ... */
    static int applicationNumber(String workflowId) {
        return (int)(Long.parseLong(workflowId) % 1_000_000 / 10_000) + 1;
    }

    static boolean inOutage(String outage, String workflowId) {
        try {
            String[] range = outage.split("-");
            int from = Integer.parseInt(range[0].trim()), to = Integer.parseInt(range[range.length - 1].trim());
            int number = applicationNumber(workflowId);
            return number >= from && number <= to;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
