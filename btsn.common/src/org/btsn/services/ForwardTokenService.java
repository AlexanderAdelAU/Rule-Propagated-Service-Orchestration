package org.btsn.services;

import org.btsn.business.BaseBusinessService;

/** Carries model data through the existing invocation boundary. */
public class ForwardTokenService extends BaseBusinessService {
    public String processToken(String token) {
        return businessResult("token", parseObject(token));
    }
}
