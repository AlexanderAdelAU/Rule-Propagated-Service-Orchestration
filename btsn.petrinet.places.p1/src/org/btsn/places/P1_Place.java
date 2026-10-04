package org.btsn.places;

import org.btsn.base.BaseBusinessPetriNetPlace;
import org.btsn.business.financial.IdentityVerificationService;
import org.btsn.business.financial.ValidationService;

/**
 * Physical P1 host adapter.
 *
 * Business behaviour is owned by logical business-service implementations in
 * org.btsn.business.financial. P1 retains these operation signatures only as
 * the current runtime host boundary; physical placement is a deployment choice.
 */
public class P1_Place extends BaseBusinessPetriNetPlace {

    private static final String PLACE_IDENTIFIER = "P1";

    private final ValidationService validationService = new ValidationService();
    private final IdentityVerificationService identityVerificationService = new IdentityVerificationService();

    public P1_Place(String sequenceID) {
        super(sequenceID, PLACE_IDENTIFIER);
    }

    /**
     * Transitional host adapter for ValidationService.processToken.
     */
    public String processToken(String token) {
        return validationService.processToken(token);
    }

    /**
     * Preserved host adapter for IdentityVerificationService.
     */
    public String verifyApplicantIdentity(String token) {
        return identityVerificationService.verifyApplicantIdentity(token);
    }
}
