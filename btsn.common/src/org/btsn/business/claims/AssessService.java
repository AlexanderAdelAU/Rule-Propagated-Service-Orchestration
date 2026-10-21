package org.btsn.business.claims;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Assesses a complex claim and accepts or rejects it.
 *
 * Starting code written by the process editor when AssessService was added to the catalogue. It compiles and
 * keeps the contract, so the process can be run now; replace the body of assess with the real logic.
 *
 * Returns: The assessment. Routes on "accept" or "reject".
 */
public class AssessService extends BaseBusinessService {

    /** Calls so far on this host: the starting code takes the routes in turn, one per call. */
    private static final java.util.concurrent.atomic.AtomicInteger CALLS = new java.util.concurrent.atomic.AtomicInteger();

    private final String sequenceId;

    public AssessService() { this(""); }

    /** The host passes the token's sequence id. */
    public AssessService(String sequenceId) { this.sequenceId = sequenceId == null ? "" : sequenceId; }

    /** token -> token */
    @SuppressWarnings("unchecked")
    public String assess(String token) {
        // RPSO-STARTING-CODE: delete this line when the real logic replaces the starting code below.
        // TODO: the real logic. Read the inputs (each is the text the previous place published, often JSON;
        // BaseBusinessService's textValue and numberValue read fields from it), decide, and fill the result.
        // Until then it returns the routes in turn, one per call, so every arc leaving the place is tried.
        String[] routes = {"accept", "reject"};
        String route = routes[(CALLS.incrementAndGet() - 1) % routes.length];

        JSONObject result = new JSONObject();
        result.put("routing_decision", routingDecision(route));
        return businessResult("token", result);
    }
}
