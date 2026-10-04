package org.btsn.places;

import org.btsn.base.BaseBusinessPetriNetPlace;
import org.btsn.business.financial.DecisionService;

/**
 * Physical P5 host adapter for the currently deployed DecisionService.
 */
public class P5_Place extends BaseBusinessPetriNetPlace {

    private static final String PLACE_IDENTIFIER = "P5";

    private final DecisionService decisionService = new DecisionService();

    public P5_Place(String sequenceID) {
        super(sequenceID, PLACE_IDENTIFIER);
    }

    public String processToken(String underwritingResults) {
        return decisionService.processToken(underwritingResults);
    }
}
