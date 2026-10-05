package org.btsn.services;

/** Model operation with an explicit token contract. */
public class FinalMergeTokenService extends BaseModelTokenService {
    public String processToken(String first, String second) {
        return merge("token", first, second);
    }
}
