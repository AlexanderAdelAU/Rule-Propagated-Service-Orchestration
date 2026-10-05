package org.btsn.services;

/** Model operation with an explicit token contract. */
public class MergeTokenService extends BaseModelTokenService {
    public String processToken(String first, String second) {
        return merge("token_branch2", first, second);
    }
}
