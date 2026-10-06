package org.btsn.services;

/** Preserves stochastic processing independently of the orchestration host. */
public final class StochasticSideTokenService extends StochasticModelTokenService {
    public StochasticSideTokenService(String sequence, String runtime, String context) {
        super(sequence, runtime, context, "token_branch1");
    }
}
