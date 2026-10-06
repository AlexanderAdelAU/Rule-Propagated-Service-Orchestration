package org.btsn.services;

/** Model operation with an explicit token contract. */
public class SideTokenService extends BaseModelTokenService {
    public String processToken(String token) {
        return carry("token_branch1", token);
    }
}
