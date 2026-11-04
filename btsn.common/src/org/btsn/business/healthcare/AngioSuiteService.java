package org.btsn.business.healthcare;

import org.json.simple.JSONObject;

/**
 * The stroke centre's angiography suite, where clots are retrieved (host: the comprehensive stroke centre).
 *
 * holdSuite: screenResults -> suiteHold. On a stroke alert the suite and its team are held for the patient before
 * they reach hospital, so a clot can be retrieved without waiting for the room. Recorded against the stroke code
 * (SuiteHolds).
 *
 * releaseSuite: token -> releaseResults. Compensation: the stand-down process releases the hold when the stroke code
 * decides the patient will not need clot retrieval. Its token was launched by the stroke code's Signal node; the
 * signal header names the stroke code's workflow (source_workflow). Safe to repeat. Routes on "released" or
 * "nothing_to_release" (no hold, or already released).
 */
public class AngioSuiteService extends StrokeServiceBase {

    @SuppressWarnings("unchecked")
    public String holdSuite(String screenResults) {
        String workflowId = workflowId(screenResults);
        JSONObject findings = new JSONObject();
        findings.put("suite", "Angiography suite 1");
        if (workflowId != null) {
            SuiteHolds.hold(workflowId);
            findings.put("suite_held", true);
            findings.put("holds_open", SuiteHolds.held());
        }
        findings.put("lvo_suspected", Boolean.parseBoolean(textValue(screenResults, "lvo_suspected", "false")));
        return strokeResult("suiteHold", strokeCase(screenResults), workflowId, findings, "held");
    }

    @SuppressWarnings("unchecked")
    public String releaseSuite(String token) {
        String workflowId = textValue(token, "source_workflow", "UNKNOWN");
        SuiteHolds.Release outcome = SuiteHolds.release(workflowId);
        String route = outcome == SuiteHolds.Release.RELEASED ? "released" : "nothing_to_release";

        JSONObject result = new JSONObject();
        result.put("stroke_workflow", workflowId);
        result.put("reason", textValue(token, "reason", ""));
        result.put("release", outcome.name().toLowerCase());
        result.put("holds_open", SuiteHolds.held());
        result.put("routing_decision", routingDecision(route));
        return businessResult("releaseResults", result);
    }
}
