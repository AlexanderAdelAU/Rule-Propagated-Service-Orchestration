package org.btsn.services;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/** Plain model data operations. The host owns synchronization and timing. */
public abstract class BaseModelTokenService extends BaseBusinessService {
    protected final String carry(String attribute, String token) {
        return businessResult(attribute, parseObject(token));
    }
    @SuppressWarnings("unchecked")
    protected final String merge(String attribute, String first, String second) {
        JSONArray branches = new JSONArray();
        branches.add(parseObject(first));
        branches.add(parseObject(second));
        JSONObject result = new JSONObject();
        result.put("branches", branches);
        return businessResult(attribute, result);
    }
}
