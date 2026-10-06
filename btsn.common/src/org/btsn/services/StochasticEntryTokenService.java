package org.btsn.services;

/** Preserves stochastic processing independently of the orchestration host. */
public final class StochasticEntryTokenService extends StochasticModelTokenService {
    public StochasticEntryTokenService(String sequence, String runtime, String context) {
        super(sequence, runtime, context, "token");
    }
}
