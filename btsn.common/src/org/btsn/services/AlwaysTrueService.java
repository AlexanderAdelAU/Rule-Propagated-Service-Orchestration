package org.btsn.services;

/**
 * Deterministic Boolean operation: always true. A place using it always takes its true route, so runs repeat
 * exactly, and timing measurements are not mixed with random routing. Orchestration and token handling belong
 * to the caller.
 */
public final class AlwaysTrueService {
    public boolean processToken(String data) { return true; }
}
