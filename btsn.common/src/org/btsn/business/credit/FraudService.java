package org.btsn.business.credit;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * It validates the attributes and details of the application
 *
 * Starting code written by the process editor when FraudService was added to the catalogue. It compiles and
 * keeps the contract, so the process can be run now; replace the body of FraudeAssessment with the real logic.
 *
 * Returns: Assessement
 */
public class FraudService extends BaseBusinessService {

    private final String sequenceId;

    public FraudService() { this(""); }

    /** The host passes the token's sequence id. */
    public FraudService(String sequenceId) { this.sequenceId = sequenceId == null ? "" : sequenceId; }

    /** token -> token */
    @SuppressWarnings("unchecked")
    public String FraudeAssessment(String token) {
        // RPSO-STARTING-CODE: delete this line when the real logic replaces the starting code below.
        // TODO: the real logic. Read the inputs (each is the text the previous place published, often JSON;
        // BaseBusinessService's textValue and numberValue read fields from it), decide, and fill the result.
        // Until then it returns the routes in turn, so every arc leaving the place is tried.
        String[] routes = {"clear", "review"};
        String route = routes[(tokenNumber(sequenceId) - 1) % routes.length];

        JSONObject result = new JSONObject();
        result.put("routing_decision", routingDecision(route));
        return businessResult("token", result);
    }

    /** 1 for the first token of a version, 2 for the second, ... (token ids are base + n x 10000). */
    static int tokenNumber(String sequenceId) {
        try {
            return (int)((Long.parseLong(sequenceId.trim()) % 1_000_000L) / 10_000L) + 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
