package org.btsn.services;

/** Preserves stochastic processing independently of the orchestration host. */
public final class StochasticBranchOneTokenService extends StochasticModelTokenService {
    public StochasticBranchOneTokenService(String sequence, String runtime, String context) {
        super(sequence, runtime, context, "token_branch1");
    }
}
