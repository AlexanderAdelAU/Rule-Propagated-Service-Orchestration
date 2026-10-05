package org.btsn.services;

/** Model operation with an explicit token contract. */
public class BranchTwoTokenService extends BaseModelTokenService {
    public String processToken(String token) {
        return carry("token_branch2", token);
    }
}
