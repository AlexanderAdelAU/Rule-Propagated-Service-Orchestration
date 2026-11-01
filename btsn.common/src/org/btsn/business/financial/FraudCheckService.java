package org.btsn.business.financial;

import java.util.concurrent.ThreadLocalRandom;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Logical Financial FraudCheckService business implementation.
 */
public class FraudCheckService extends BaseBusinessService {

    /** The chance that the fraud bureau cannot be reached for an application: 1 in 5. */
    static final double UNAVAILABLE_PROBABILITY = 0.2;

    /**
     * validationResults -> fraudCheckResults
     *
     * The fraud bureau is sometimes unavailable: for an application in a run (one with a workflow number), the check
     * reports fraud_status "unavailable" with probability UNAVAILABLE_PROBABILITY. Its credit check has already
     * recorded an enquiry; underwriting, without a fraud result, abandons the application, and the loan process's
     * Signal node launches Loan Compensation to withdraw that enquiry.
     */
    @SuppressWarnings("unchecked")
    public String processToken(String validationResults) {
        String applicationId = textValue(validationResults, "application_id", "APP-UNKNOWN");
        String fraudRisk = textValue(validationResults, "fraud_risk", "unknown");
        String fraudStatus = "high".equalsIgnoreCase(fraudRisk) ? "flagged" : "clear";

        String workflowId = textValue(validationResults, "workflow_id", null);
        if (workflowId != null && ThreadLocalRandom.current().nextDouble() < UNAVAILABLE_PROBABILITY) fraudStatus = "unavailable";

        JSONObject result = new JSONObject();
        result.put("application_id", applicationId);
        result.put("fraud_risk", fraudRisk);
        result.put("fraud_status", fraudStatus);

        return businessResult("fraudCheckResults", result);
    }
}
