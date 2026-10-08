package org.btsn.services;

import java.util.Objects;
import java.util.Random;

/** Reusable random Boolean operation. Orchestration and token handling belong to the caller. */
public final class StochasticService {
    private final Random random;
    public StochasticService() { this(new Random()); }
    public StochasticService(Random random) { this.random = Objects.requireNonNull(random); }
    public boolean processToken(String data) { return random.nextBoolean(); }
}
