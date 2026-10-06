package org.btsn.services;

/** Preserves stochastic processing independently of the orchestration host. */
public final class StochasticForwardTokenService extends StochasticModelTokenService {
    public StochasticForwardTokenService(String sequence, String runtime, String context) {
        super(sequence, runtime, context, "token");
    }
}
