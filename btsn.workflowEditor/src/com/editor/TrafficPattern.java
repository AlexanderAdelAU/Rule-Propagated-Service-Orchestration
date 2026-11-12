package com.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An event generator's Rate (ms): how its tokens are spread in time.
 *
 * <pre>
 *   1000                      one token every 1000 ms
 *   0                         all at once: a burst
 *   1000 x10, 0 x12, 1000     10 one every 1000 ms, then 12 at once, then one every 1000 ms for the rest
 * </pre>
 *
 * Each part is an interval in ms and, except for the last, how many tokens it covers ("x10"). Each token comes its
 * part's interval after the one before; a burst (0) arrives when the next token would have, all at once.
 */
final class TrafficPattern {
    static final class Part { final int intervalMs; final int count; Part(int i, int c) { intervalMs = i; count = c; } }

    final List<Part> parts = new ArrayList<>();

    private TrafficPattern() {}

    /** The pattern, or null when the text is not one; why() says what is wrong. */
    static TrafficPattern parse(String text) { return why(text) == null ? build(text) : null; }

    /** What is wrong with the text as a pattern, or null when it is one. */
    static String why(String text) {
        if (text == null || text.trim().isEmpty()) return "Give an interval in ms, e.g. 1000; 0 sends a burst";
        String[] items = text.split(",");
        for (int i = 0; i < items.length; i++) {
            String item = items[i].trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,7})(?:\\s*[xX×*]\\s*(\\d{1,6}))?").matcher(item);
            if (!m.matches()) return "\"" + item + "\" is not an interval in ms, optionally with a count, like 1000 x10";
            if (m.group(2) == null && i < items.length - 1) return "Only the last part may leave out its count: \"" + item + "\" needs one, like " + item + " x10";
            if (m.group(2) != null && Integer.parseInt(m.group(2)) == 0) return "\"" + item + "\" covers no tokens";
        }
        return null;
    }

    private static TrafficPattern build(String text) {
        TrafficPattern p = new TrafficPattern();
        for (String item : text.split(",")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,7})(?:\\s*[xX×*]\\s*(\\d{1,6}))?").matcher(item.trim());
            m.matches();
            p.parts.add(new Part(Integer.parseInt(m.group(1)), m.group(2) == null ? -1 : Integer.parseInt(m.group(2))));
        }
        return p;
    }

    /** The first part's interval: the rate of a steady pattern. */
    int firstInterval() { return parts.get(0).intervalMs; }

    boolean steady() { return parts.size() == 1; }

    /** When each of the tokens is sent, as ms from the start to how many are sent then. */
    Map<Long, Integer> schedule(int tokens) {
        Map<Long, Integer> at = new TreeMap<>();
        long time = 0;
        int previousInterval = 0, sent = 0;
        for (int p = 0; p < parts.size() && sent < tokens; p++) {
            Part part = parts.get(p);
            int count = part.count < 0 ? tokens - sent : Math.min(part.count, tokens - sent);
            for (int k = 0; k < count; k++) {
                if (sent > 0) time += part.intervalMs == 0 ? (k == 0 ? previousInterval : 0) : part.intervalMs;
                at.merge(time, 1, Integer::sum);
                sent++;
            }
            previousInterval = part.intervalMs;
        }
        // A pattern whose counts end before the tokens do repeats its last part.
        Part last = parts.get(parts.size() - 1);
        while (sent < tokens) { time += last.intervalMs; at.merge(time, 1, Integer::sum); sent++; }
        return at;
    }

    /** The pattern in words, for Build's summary. */
    String describe() {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            String how = part.intervalMs == 0 ? "at once" : "one every " + part.intervalMs + " ms";
            words.add(part.count < 0 ? (parts.size() == 1 ? how : "then " + how + " for the rest") : part.count + " " + how);
        }
        return String.join(", ", words);
    }
}
