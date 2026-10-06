package org.btsn.services;

/** Preserves stochastic processing independently of the orchestration host. */
public final class StochasticReturnTokenService extends StochasticModelTokenService {
    public StochasticReturnTokenService(String sequence, String runtime, String context) {
        super(sequence, runtime, context, "token_branch2");
    }
}
