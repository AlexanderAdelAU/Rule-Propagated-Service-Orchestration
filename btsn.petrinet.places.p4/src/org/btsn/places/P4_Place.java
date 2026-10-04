package org.btsn.places;

import org.btsn.base.BaseBusinessPetriNetPlace;
import org.btsn.business.financial.UnderwritingService;

/**
 * Physical P4 host adapter for the currently deployed UnderwritingService.
 *
 * Join synchronization remains entirely in the orchestration infrastructure.
 */
public class P4_Place extends BaseBusinessPetriNetPlace {

    private static final String PLACE_IDENTIFIER = "P4";

    private final UnderwritingService underwritingService = new UnderwritingService();

    public P4_Place(String sequenceID) {
        super(sequenceID, PLACE_IDENTIFIER);
    }

    public String processToken(String creditCheckResults, String fraudCheckResults) {
        return underwritingService.processToken(creditCheckResults, fraudCheckResults);
    }
}
