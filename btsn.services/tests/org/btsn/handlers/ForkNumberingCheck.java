package org.btsn.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Token numbers at forks and joins: a join's survivor holds the numbers the join consumed, and a fork hands
 * those numbers back out. Ordinary nets keep the numbers they always had; rings never duplicate a number.
 */
public final class ForkNumberingCheck {
    private static int checks;

    /** A token in flight: its number and its heldIds header. */
    static final class Token {
        private final int id;
        private final String held;
        Token(int id, String held) { this.id = id; this.held = held; }
        int id() { return id; }
        String held() { return held; }
        @Override public String toString() { return id + (held == null || held.isEmpty() ? "" : "[" + held + "]"); }
    }

    public static void main(String[] args) {
        ordinaryNetsKeepTheirNumbers();
        joinThenForkHandsBackTheConsumedNumbers();
        sparesComeBackAtAWiderFork();
        ringsNeverDuplicate();
        System.out.println("PASS: " + checks + " fork/join numbering checks");
    }

    /** Wherever the old rule (survivor, survivor+1, ...) could not clash, the numbers are unchanged. */
    static void ordinaryNetsKeepTheirNumbers() {
        equal(new int[] {1000001, 1000002}, fork(new Token(1000000, ""), 2), "first fork, two branches");
        equal(new int[] {1000001, 1000002, 1000003}, fork(new Token(1000000, ""), 3), "first fork, three branches");
        equal(new int[] {2010001, 2010002}, fork(new Token(2010000, null), 2), "another workflow and version");
        // A join of consecutive children, then a fork of any width: survivor, survivor+1, ... as before.
        for (int survivor = 1; survivor <= 5; survivor++)
            for (int consumed = 1; consumed <= 4; consumed++)
                for (int width = 1; width <= 6; width++) {
                    List<Token> in = new ArrayList<>();
                    for (int i = 0; i < consumed; i++) in.add(new Token(1000000 + survivor + i, ""));
                    int[] old = new int[width];
                    for (int i = 0; i < width; i++) old[i] = 1000000 + survivor + i;
                    equal(old, fork(join(in), width), "join of " + consumed + " from " + survivor + ", fork of " + width);
                }
    }

    /** The case that used to clash: a join of 03 and 05 forked into 03 and 04 while 04 was still live. */
    static void joinThenForkHandsBackTheConsumedNumbers() {
        Token survivor = join(List.of(new Token(1000005, ""), new Token(1000003, "")));
        check(survivor.id() == 1000003, "the lowest child survives the join");
        check("1000003,1000005".equals(survivor.held()), "the survivor holds both consumed numbers: " + survivor.held());
        equal(new int[] {1000003, 1000005}, fork(survivor, 2), "the fork hands back 03 and 05, not 03 and 04");
        Token[] children = children(survivor, 2);
        check(children[0].held().isEmpty() && children[1].held().isEmpty(), "children of an even fork hold only themselves");
        check(ServiceThread.formatHeldIds(ServiceThread.heldIds("", 1000004), 1000004).isEmpty(), "a lone token's header stays empty");
        check(ServiceThread.heldIds(null, 1000004).equals(new TreeSet<>(List.of(1000004))), "a token without the header holds itself");
    }

    /** A join of three forked into two keeps the spare with its first child; a later wider fork uses it. */
    static void sparesComeBackAtAWiderFork() {
        Token survivor = join(List.of(new Token(1000001, ""), new Token(1000002, ""), new Token(1000003, "")));
        Token[] two = children(survivor, 2);
        check(two[0].id() == 1000001 && two[1].id() == 1000002, "narrower fork: 01 and 02");
        check("1000001,1000003".equals(two[0].held()), "01 carries the spare 03: " + two[0].held());
        Token rejoined = join(List.of(two[0], new Token(1000004, "")));
        equal(new int[] {1000001, 1000003, 1000004}, fork(rejoined, 3), "the wider fork reuses the spare 03");
    }

    /**
     * A closed net (as in the dining philosophers): five tokens, and at every step two of them join and fork
     * back into two. The live numbers must stay the original five, each exactly once.
     */
    static void ringsNeverDuplicate() {
        Random random = new Random(7);
        List<Token> live = new ArrayList<>();
        for (int i = 1; i <= 5; i++) live.add(new Token(1000000 + i, ""));
        Set<Integer> original = ids(live);
        for (int step = 0; step < 20000; step++) {
            Token a = live.remove(random.nextInt(live.size()));
            Token b = live.remove(random.nextInt(live.size()));
            live.addAll(Arrays.asList(children(join(List.of(a, b)), 2)));
            Set<Integer> now = ids(live);
            if (now.size() != live.size() || !now.equals(original))
                throw new AssertionError("step " + step + ": live numbers " + live + " are not the original five");
        }
        checks++;
        // The old rule duplicates in the same ring within a few steps.
        live.clear();
        for (int i = 1; i <= 5; i++) live.add(new Token(1000000 + i, ""));
        boolean duplicated = false;
        for (int step = 0; step < 200 && !duplicated; step++) {
            Token a = live.remove(random.nextInt(live.size()));
            Token b = live.remove(random.nextInt(live.size()));
            int s = Math.min(a.id(), b.id());
            live.add(new Token(s, ""));
            live.add(new Token(s + 1, ""));
            duplicated = ids(live).size() != live.size();
        }
        check(duplicated, "the old numbering duplicates in a ring (sanity check of the scenario)");
    }

    /** A join as ServiceThread completes it: the lowest number survives and holds all consumed numbers. */
    static Token join(List<Token> inputs) {
        TreeSet<Integer> held = new TreeSet<>();
        for (Token t : inputs) held.addAll(ServiceThread.heldIds(t.held(), t.id()));
        int survivor = inputs.stream().mapToInt(Token::id).min().getAsInt();
        return new Token(survivor, ServiceThread.formatHeldIds(held, survivor));
    }

    static int[] fork(Token parent, int count) {
        return TransitionHandler.forkNumbers(parent.id(), parent.held(), count).ids;
    }

    static Token[] children(Token parent, int count) {
        TransitionHandler.ForkNumbers numbers = TransitionHandler.forkNumbers(parent.id(), parent.held(), count);
        Token[] out = new Token[count];
        for (int i = 0; i < count; i++) out[i] = new Token(numbers.ids[i], numbers.held[i]);
        return out;
    }

    static Set<Integer> ids(List<Token> tokens) {
        Set<Integer> ids = new HashSet<>();
        for (Token t : tokens) ids.add(t.id());
        return ids;
    }

    static void equal(int[] expected, int[] actual, String what) {
        check(Arrays.equals(expected, actual), what + ": expected " + Arrays.toString(expected) + ", got " + Arrays.toString(actual));
    }

    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        checks++;
    }
}
