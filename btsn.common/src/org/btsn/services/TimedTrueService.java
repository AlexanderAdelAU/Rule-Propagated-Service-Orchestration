package org.btsn.services;

import java.util.Objects;
import java.util.Random;

/**
 * Takes its time, then returns true: a step that does real work, such as a philosopher eating.
 * Each call lasts a random time between MIN_MS and MAX_MS, so the place stays busy the way a real service would.
 */
public final class TimedTrueService {
    static final long MIN_MS = 500, MAX_MS = 1500;
    private final Random random;
    public TimedTrueService() { this(new Random()); }
    public TimedTrueService(Random random) { this.random = Objects.requireNonNull(random); }
    public boolean processToken(String data) {
        long ms = MIN_MS + (long)(random.nextDouble() * (MAX_MS - MIN_MS));
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }
}
