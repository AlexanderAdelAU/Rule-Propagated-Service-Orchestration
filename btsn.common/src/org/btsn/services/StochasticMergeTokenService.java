package org.btsn.services;

/** Preserves stochastic processing independently of the orchestration host. */
public final class StochasticMergeTokenService extends StochasticModelTokenService {
    public StochasticMergeTokenService(String sequence, String runtime, String context) {
        super(sequence, runtime, context, "token");
    }
}
