package org.btsn.handlers;

import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A place never lets one token replace another with the same number. Run in btsn.rpso.host (the reactor reads
 * ServiceLoaderQueries/loaderSettings.xml) with the host's PayLoad/payload.xml as argument.
 *
 * A different token arriving under a number already waiting at the place (queued, at a join, or held there for a
 * later fork) is renamed to a free number of its workflow; the same token delivered twice is discarded; when no
 * number of its workflow is free, the token is reported as failed rather than lost silently.
 */
public final class DuplicateNumberCheck {
    private static int checks;
    private static String template;

    public static void main(String[] args) throws Exception {
        template = Files.readString(Path.of(args[0]));
        rules();
        try {
            reactor();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println("PASS: " + checks + " duplicate token number checks");
        System.exit(0);   // the reactor's receive threads would keep the JVM running
    }

    static void rules() {
        check(TokenNumbers.classify("a", null) == TokenNumbers.Arrival.NEW, "a number not waiting here is new");
        check(TokenNumbers.classify("P5@1#3", "P5@1#3") == TokenNumbers.Arrival.REPEAT, "same stamp: a repeat");
        check(TokenNumbers.classify(null, "") == TokenNumbers.Arrival.REPEAT, "two unstamped (generator) tokens: a repeat");
        check(TokenNumbers.classify("P5@1#4", "P1@1#1") == TokenNumbers.Arrival.DUPLICATE, "different stamps: a duplicate");
        check(TokenNumbers.classify("P5@1#4", "") == TokenNumbers.Arrival.DUPLICATE, "stamped against unstamped: a duplicate");
        check(TokenNumbers.classify("", TokenNumbers.HELD + "1000001") == TokenNumbers.Arrival.DUPLICATE,
              "a number another token holds for a later fork: a duplicate");
        check(TokenNumbers.renumber(1000003, Set.of(1000003)) == 1000004, "next free number");
        check(TokenNumbers.renumber(1000003, Set.of(1000003, 1000004, 1000005)) == 1000006, "skips numbers in use");
        check(TokenNumbers.renumber(1000099, Set.of(1000099)) == 1000001, "wraps from 99 to 01, never to 00 or the next workflow");
        Set<Integer> all = new HashSet<>();
        for (int i = 1; i <= 99; i++) all.add(2030000 + i);
        check(TokenNumbers.renumber(2030007, all) == -1, "no free number in the workflow: -1");
        all.remove(2030050);
        check(TokenNumbers.renumber(2030007, all) == 2030050, "the one free number is found");
        String packet = packet(1000003, "P5@1#9", "1000003,1000007", "{\"tokenId\":\"1000003\",\"other\":\"1000030\"}");
        TreeMap<String, String> header = new TreeMap<>();
        header.put("sequenceId", "1000003");
        header.put(ServiceThread.HELD_IDS, "1000003,1000007");
        String renamed = TokenNumbers.renumberPacket(packet, header, 1000003, 1000008);
        check(renamed.contains("<sequenceId>1000008</sequenceId>"), "header renumbered");
        check(renamed.contains("<heldIds>1000007,1000008</heldIds>"), "its held numbers keep the spare and take the new number");
        check(renamed.contains("\\\"tokenId\\\":\\\"1000008\\\"") || renamed.contains("\"tokenId\":\"1000008\""), "the token's own id in its data");
        check(renamed.contains("1000030"), "other numbers in the data untouched");
        check("1000008".equals(header.get("sequenceId")), "the caller's header follows");
    }

    static void reactor() throws Exception {
        int port;
        try (DatagramSocket probe = new DatagramSocket(0)) { port = probe.getLocalPort(); }
        EventReactor reactor = new EventReactor("ip0", Integer.toString(port));
        Map<Integer, String> atJoin = new HashMap<>();
        List<String> renames = new ArrayList<>();
        List<String> repeats = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        reactor.setDropHandler((id, place, reason) -> failures.add(id + " " + reason));
        reactor.setTokenNumberListener(new EventReactor.TokenNumberListener() {
            public Map<Integer, String> waitingAtJoins() { return new HashMap<>(atJoin); }
            public void renamed(int oldId, int newId, String place) { renames.add(oldId + "->" + newId); }
            public void repeated(int tokenId, String place) { repeats.add(Integer.toString(tokenId)); }
        });

        reactor.putScheduledToken(packet(1000002, "P1@1#1", "", "{}"));
        reactor.putScheduledToken(packet(1000002, "P5@1#7", "", "{\"tokenId\":\"1000002\"}"));
        check(renames.equals(List.of("1000002->1000003")), "a different token under a queued number is renamed: " + renames);
        check(reactor.getQueueSize() == 2, "both tokens queued, none replaced");

        reactor.putScheduledToken(packet(1000002, "P1@1#1", "", "{}"));
        check(repeats.equals(List.of("1000002")) && reactor.getQueueSize() == 2, "the same token again is discarded as a repeat");

        atJoin.put(1000005, "P3@1#2");
        reactor.putScheduledToken(packet(1000005, "P6@1#8", "", "{}"));
        check(renames.contains("1000005->1000006"), "a duplicate of a number waiting at the join is renamed: " + renames);

        reactor.putScheduledToken(packet(1000010, "P2@1#3", "1000010,1000011", "{}"));
        reactor.putScheduledToken(packet(1000011, "P7@1#4", "", "{}"));
        check(renames.contains("1000011->1000012"), "a number a queued token holds for a later fork is not reused: " + renames);

        reactor.putScheduledToken(packet(1000020, "", "", "{}"));
        reactor.putScheduledToken(packet(1000020, null, "", "{}"));
        check(repeats.contains("1000020"), "a generator's token delivered twice is a repeat");

        for (int i = 1; i <= 99; i++) if (i != 1) atJoin.put(1010000 + i, "P1@1#" + (100 + i));
        reactor.putScheduledToken(packet(1010001, "P1@1#500", "", "{}"));
        reactor.putScheduledToken(packet(1010001, "P1@1#501", "", "{}"));
        check(failures.size() == 1 && failures.get(0).startsWith("1010001 "), "no free number: reported as failed, not lost silently: " + failures);

        int admin = 999010000;
        reactor.putScheduledToken(packet(admin, "", "", "{}"));
        reactor.putScheduledToken(packet(admin, "x", "", "{}"));
        check(renames.stream().noneMatch(r -> r.startsWith(admin + "->")), "administrative tokens are never renamed");

        Set<Integer> drained = new HashSet<>();
        String renamedPacket = null;
        while (reactor.getQueueSize() > 0) {
            TreeMap<Long, String> next = reactor.getScheduledToken();
            String p = next.firstEntry().getValue();
            int id = Integer.parseInt(p.replaceAll("(?s).*<sequenceId>(\\d+)</sequenceId>.*", "$1"));
            drained.add(id);
            if (id == 1000003) renamedPacket = p;
        }
        check(drained.containsAll(Set.of(1000002, 1000003, 1000006, 1000010, 1000012, 1000020, 1010001)),
              "every token came out under its own number: " + drained);
        check(renamedPacket != null && renamedPacket.contains("1000003") && renamedPacket.contains("P5@1#7"),
              "the renamed token keeps its stamp and data, under its new number");

        atJoin.clear();
        int before = renames.size();
        reactor.putScheduledToken(packet(1000002, "P9@1#1", "", "{}"));
        check(renames.size() == before && repeats.size() == 2, "once a number has left the place it is free again");
    }

    static String packet(int id, String origin, String held, String data) {
        String p = template.replace("<sequenceId>0</sequenceId>", "<sequenceId>" + id + "</sequenceId>"
                + (origin == null ? "" : "<origin>" + origin + "</origin>")
                + "<heldIds>" + held + "</heldIds>");
        p = p.replace("<serviceName>null</serviceName>", "<serviceName>P4_Place</serviceName>");
        p = p.replace("<attributeValue>0</attributeValue>", "<attributeValue>" + data.replace("\"", "&quot;") + "</attributeValue>");
        return p.replace("<notAfter>0</notAfter>", "<notAfter>" + (System.currentTimeMillis() + 600_000) + "</notAfter>");
    }

    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        checks++;
    }
}
