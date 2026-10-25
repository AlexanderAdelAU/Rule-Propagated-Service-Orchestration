package com.editor;

import com.editor.animator.AnimationSegment;
import com.editor.animator.Phase;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import javax.swing.SwingUtilities;

/**
 * A token ends where the process says: at the terminate node its T_out's arc leads to, or at the T_out itself when
 * that T_out is the terminate node. The financial pre-screen ends valid applications at T_out_CreditCheck (a
 * TerminateNode with no arc out); the replay must not send them to Invalid Pre-Screen, which only T_out_Validation's
 * invalid arc reaches.
 */
public final class TerminateReplayCheck {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile();
        File process = new File(root, "btsn.common/ProcessDefinitionFolder/financial/Workflow/FinancialSystem_Stage3_PreScreen_Workflow.json");
        String run = Files.readString(root.toPath().resolve("btsn.workflowEditor/tests/fixtures/terminate_tout/Analysis_PreScreen.txt"));
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process); canvas.loadFromJSON(Files.readString(process.toPath()));
                TokenAnimator animator = new TokenAnimator(); animator.setCanvas(canvas); animator.parseOutput(run);
                Field f = TokenAnimator.class.getDeclaredField("segments"); f.setAccessible(true);
                Map<String, Integer> endings = new TreeMap<>();
                Set<String> reachedCreditCheck = new HashSet<>();
                for (Object o : (List<?>) f.get(animator)) {
                    AnimationSegment seg = (AnimationSegment) o;
                    if (seg.phase == Phase.AT_TERMINATE || seg.phase == Phase.TRAVELING_TO_TERMINATE) endings.merge(seg.phase + " " + seg.terminateId, 1, Integer::sum);
                    if ("CreditCheck".equals(seg.placeId) || "P2_Place".equals(seg.placeId) || "CreditCheckService".equals(seg.placeId)) reachedCreditCheck.add(seg.tokenId + "");
                }
                check(endings.keySet().stream().noneMatch(k -> k.endsWith("Invalid Pre-Screen")), "Valid applications replayed to Invalid Pre-Screen: " + endings);
                check(endings.getOrDefault("AT_TERMINATE T_out_CreditCheck", 0) == 10, "Not all 10 tokens end at T_out_CreditCheck: " + endings);
            } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError("Terminate replay", failure[0]);
        System.out.println("PASS: the pre-screen run replays its 10 valid applications to T_out_CreditCheck (a T_out that is itself the terminate node), none to Invalid Pre-Screen");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
