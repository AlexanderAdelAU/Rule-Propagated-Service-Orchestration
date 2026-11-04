package org.btsn.business.healthcare;

import org.json.simple.JSONObject;

/**
 * The ambulance service's stroke screen, on the road (host: the ambulance service).
 *
 * token -> screenResults. Routes on "stroke_alert" (pre-notify the stroke centre and the receiving hospital) or
 * "standard_ed" (no stroke code: the patient goes to the nearest emergency department as usual):
 * - FAST negative (face, arm, speech all normal): standard_ed.
 * - Blood glucose under 4.0 mmol/L: standard_ed. Low glucose mimics a stroke; it is treated on the road.
 * - Onset more than 24 hours ago, or unknown: standard_ed. No reperfusion treatment is possible.
 * - Otherwise a stroke alert. A prehospital severity score of 6 or more (on the NIHSS scale) also flags a likely
 *   large-vessel occlusion, which may need clot retrieval at the stroke centre.
 */
public class StrokeScreenService extends StrokeServiceBase {

    @SuppressWarnings("unchecked")
    public String screenPatient(String token) {
        JSONObject data = parseObject(token);
        Object inner = data.get("data");
        JSONObject strokeCase = new JSONObject();
        if (inner instanceof JSONObject) strokeCase.putAll((JSONObject) inner);
        else strokeCase.putAll(data);

        boolean fast = flag(strokeCase, "fast_positive");
        double glucose = number(strokeCase, "glucose_mmol", 6.0);
        double onset = number(strokeCase, "onset_minutes", -1);
        double severity = number(strokeCase, "nihss", 0);

        String route, reason;
        if (!fast) { route = "standard_ed"; reason = "FAST negative"; }
        else if (glucose < 4.0) { route = "standard_ed"; reason = "Hypoglycaemia (" + glucose + " mmol/L): a stroke mimic, treated on the road"; }
        else if (onset < 0 || onset > 1440) { route = "standard_ed"; reason = "Onset unknown or over 24 hours: outside every reperfusion window"; }
        else { route = "stroke_alert"; reason = "FAST positive, onset " + (int) onset + " min"; }

        JSONObject findings = new JSONObject();
        findings.put("screen", route);
        findings.put("reason", reason);
        findings.put("lvo_suspected", route.equals("stroke_alert") && severity >= 6);
        findings.put("patient_id", text(strokeCase, "patientId", "UNKNOWN"));
        // The stroke code's workflow number, so the suite hold can be released against it later.
        return strokeResult("screenResults", strokeCase, textValue(token, "tokenId", null), findings, route);
    }
}
