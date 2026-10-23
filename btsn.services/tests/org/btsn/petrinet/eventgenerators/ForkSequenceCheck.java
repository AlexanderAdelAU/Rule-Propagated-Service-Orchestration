package org.btsn.petrinet.eventgenerators;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.btsn.constants.VersionConstants;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** Checks the real generic generator's child-ID contract and common parent payload. */
public final class ForkSequenceCheck {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        int parent1 = VersionConstants.V001_BASE;
        int parent2 = parent1 + VersionConstants.TOKEN_INCREMENT;
        int a = GenericPetriNetTokenGenerator.topologyChildId(parent1, 1);
        int b = GenericPetriNetTokenGenerator.topologyChildId(parent1, 2);
        check(a == 1000001 && b == 1000002, "Incorrect child IDs for parent 1000000");
        check(GenericPetriNetTokenGenerator.topologyChildId(parent2, 1) == 1010001,
            "Second parent collided with the first");
        check(VersionConstants.getParentTokenId(a) == parent1
            && VersionConstants.getParentTokenId(b) == parent1,
            "Children do not share their parent");
        try {
            GenericPetriNetTokenGenerator.topologyChildId(parent1, 100);
            throw new AssertionError("Accepted branch 100 despite host decoding only 2 digits");
        } catch (IllegalArgumentException expected) { }

        Class<?> generator = GenericPetriNetTokenGenerator.class;
        set(generator, "ruleBaseVersion", "v001");
        set(generator, "tokenData", "mesh=alpha,eligible=true");
        set(generator, "timeToExpire", 120000L);
        Method token = generator.getDeclaredMethod("createPetriNetToken",
            int.class, int.class, int.class, int.class, long.class);
        token.setAccessible(true);
        long now = 1760000000000L;
        set(generator, "targetPlaceName", "P1_Place");
        JSONObject one = (JSONObject)new JSONParser().parse(
            (String)token.invoke(null, a, parent1, 1, 2, now));
        set(generator, "targetPlaceName", "P2_Place");
        JSONObject two = (JSONObject)new JSONParser().parse(
            (String)token.invoke(null, b, parent1, 2, 2, now));

        check("1000000".equals(one.get("parentTokenId"))
            && one.get("parentTokenId").equals(two.get("parentTokenId")),
            "Parent identity not shared");
        check("1000001".equals(one.get("tokenId")) && "1000002".equals(two.get("tokenId")),
            "Child token IDs not distinct");
        check("P1_Place".equals(one.get("currentPlace"))
            && "P2_Place".equals(two.get("currentPlace")),
            "Children were not addressed to separate destinations");
        check(one.get("data").equals(two.get("data"))
            && one.get("workflow_start_time").equals(two.get("workflow_start_time"))
            && one.get("notAfter").equals(two.get("notAfter")),
            "Children do not share the same initial business data and event scope");
        check(((JSONObject)one.get("data")).get("mesh").equals("alpha"),
            "Business payload lost");
        System.out.println("PASS: parent+1/parent+2, stride, 99-branch host limit, shared payload and per-child destinations");
    }

    private static void set(Class<?> type, String field, Object value) throws Exception {
        Field f = type.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }
}
