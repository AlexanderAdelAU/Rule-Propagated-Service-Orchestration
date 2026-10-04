package org.btsn.places;

import org.btsn.base.BaseBusinessPetriNetPlace;
import org.btsn.business.financial.FraudCheckService;

/**
 * Physical P3 host adapter for the currently deployed FraudCheckService.
 */
public class P3_Place extends BaseBusinessPetriNetPlace {

    private static final String PLACE_IDENTIFIER = "P3";

    private final FraudCheckService fraudCheckService = new FraudCheckService();

    public P3_Place(String sequenceID) {
        super(sequenceID, PLACE_IDENTIFIER);
    }

    public String processToken(String validationResults) {
        return fraudCheckService.processToken(validationResults);
    }
}
