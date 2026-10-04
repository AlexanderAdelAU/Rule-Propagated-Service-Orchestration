package org.btsn.places;

import org.btsn.base.BaseBusinessPetriNetPlace;
import org.btsn.business.financial.AffordabilityAssessmentService;
import org.btsn.business.financial.CreditCheckService;

/**
 * Physical P2 host adapter.
 *
 * Business behaviour is owned by logical business-service implementations in
 * org.btsn.business.financial. P2 retains these operation signatures only as
 * the current runtime host boundary; physical placement is a deployment choice.
 */
public class P2_Place extends BaseBusinessPetriNetPlace {

    private static final String PLACE_IDENTIFIER = "P2";

    private final CreditCheckService creditCheckService = new CreditCheckService();
    private final AffordabilityAssessmentService affordabilityAssessmentService =
            new AffordabilityAssessmentService();

    public P2_Place(String sequenceID) {
        super(sequenceID, PLACE_IDENTIFIER);
    }

    /**
     * Transitional host adapter for CreditCheckService.processToken.
     */
    public String processToken(String validationResults) {
        return creditCheckService.processToken(validationResults);
    }

    /**
     * Preserved host adapter for AffordabilityAssessmentService.
     */
    public String assessAffordability(String identityVerificationResults) {
        return affordabilityAssessmentService.assessAffordability(identityVerificationResults);
    }
}
