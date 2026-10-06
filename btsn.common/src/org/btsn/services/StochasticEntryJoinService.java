package org.btsn.services;

/** Preserves stochastic processing independently of the orchestration host. */
public final class StochasticEntryJoinService extends StochasticModelTokenService {
    public StochasticEntryJoinService(String sequence, String runtime, String context) {
        super(sequence, runtime, context, "token");
    }
}
