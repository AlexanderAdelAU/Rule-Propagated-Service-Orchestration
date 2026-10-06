package org.btsn.services;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/** A deterministic model operation; timing is measured by the host. */
public class BooleanTokenService extends BaseBusinessService {
    @SuppressWarnings("unchecked")
    public String processToken(String token) {
        String outcome = textValue(token, "outcome", "true");
        if (!"true".equals(outcome) && !"false".equals(outcome)) {
            throw new IllegalArgumentException("outcome must be true or false");
        }
        JSONObject result = parseObject(token);
        result.put("outcome", Boolean.valueOf(outcome));
        result.put("routing_decision", routingDecision(outcome));
        return businessResult("token", result);
    }
}
