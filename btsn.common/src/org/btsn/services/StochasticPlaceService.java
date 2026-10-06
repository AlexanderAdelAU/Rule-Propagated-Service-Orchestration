package org.btsn.services;

import org.btsn.base.BaseStochasticPetriNetPlace;

/**
 * Independently deployable implementation of the existing stochastic service.
 * The caller supplies its runtime identity; no physical host is compiled in.
 * Processing behaviour remains in the unchanged BaseStochasticPetriNetPlace.
 */
public class StochasticPlaceService extends BaseStochasticPetriNetPlace {

    public StochasticPlaceService(String sequenceID, String runtimeIdentity) {
        super(sequenceID, runtimeIdentity);
    }

    /** Matches the invocation boundary's existing three-String constructor contract. */
    public StochasticPlaceService(String sequenceID, String runtimeIdentity, String invocationContext) {
        this(sequenceID, runtimeIdentity);
    }

    public StochasticPlaceService(String sequenceID, String runtimeIdentity, int capacity, long processingDelayMs) {
        super(sequenceID, runtimeIdentity, capacity, processingDelayMs);
    }
}
