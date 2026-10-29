package com.editor;

import com.editor.animator.TokenAnimState;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/** Regression check for compact, per-version termination markers in replay. */
public final class TerminatedTokenCountsCheck {
    public static void main(String[] args) {
        TokenAnimState red1 = new TokenAnimState("10001", "v001");
        TokenAnimState red2 = new TokenAnimState("10002", "v001");
        TokenAnimState blue = new TokenAnimState("20001", "v002");
        TokenAnimState green = new TokenAnimState("30001", "v003");

        Map<String, Integer> counts = Canvas.terminatedCountsByVersion(
                Arrays.asList(red1, green, red2, blue));
        check(counts.size() == 3, "Expected one marker for each version");
        check(counts.get("v001") == 2 && counts.get("v002") == 1 && counts.get("v003") == 1,
              "Version counts incorrect: " + counts);
        check(counts.keySet().iterator().next().equals("v001"), "Marker order must be stable");
        check(Canvas.terminatedCountsByVersion(Collections.emptyList()).isEmpty(),
              "No completed tokens should produce no markers");
        check(Canvas.terminatedCountsByVersion(null).isEmpty(),
              "Missing snapshot should produce no markers");

        System.out.println("PASS: terminated tokens are grouped by version with stable counts");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
