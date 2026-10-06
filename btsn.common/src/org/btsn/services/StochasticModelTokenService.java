package org.btsn.services;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.btsn.base.BaseStochasticPetriNetPlace;

/** Names the existing stochastic response for an explicit workflow data contract. */
public abstract class StochasticModelTokenService extends BaseStochasticPetriNetPlace {
    private final String outputAttribute;
    protected StochasticModelTokenService(String sequence, String runtime, String context, String output) {
        super(sequence, runtime);
        outputAttribute = output;
    }
    @Override public String processToken(String token) { return attribute(super.processToken(token)); }
    @Override public String processToken(String first, String second) { return attribute(super.processToken(first, second)); }
    @SuppressWarnings("unchecked")
    private String attribute(String result) {
        if ("token".equals(outputAttribute)) return result;
        try {
            JSONObject response = (JSONObject)new JSONParser().parse(result);
            if (response.containsKey("token")) response.put(outputAttribute, response.remove("token"));
            return response.toJSONString();
        } catch (Exception e) { throw new IllegalStateException("Invalid stochastic service response", e); }
    }
}
