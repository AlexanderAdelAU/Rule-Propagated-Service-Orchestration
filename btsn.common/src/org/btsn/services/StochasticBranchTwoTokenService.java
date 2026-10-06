package org.btsn.services;

/** Preserves stochastic processing independently of the orchestration host. */
public final class StochasticBranchTwoTokenService extends StochasticModelTokenService {
    public StochasticBranchTwoTokenService(String sequence, String runtime, String context) {
        super(sequence, runtime, context, "token_branch2");
    }
}
