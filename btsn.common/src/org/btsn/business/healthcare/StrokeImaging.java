package org.btsn.business.healthcare;

import org.json.simple.JSONObject;

/**
 * The regional imaging provider's stroke protocol: what RadiologyService.processImagingRequest does for a stroke
 * code. The provider's CT scanner serves every hospital that sends to it, routine patients and stroke codes alike;
 * a stroke code is scanned first because its process has the higher priority on the provider's host.
 *
 * Non-contrast CT brain, then CT angiography from the aortic arch to the vertex:
 * - Haemorrhage on the CT brain rules out clot-dissolving treatment.
 * - A large-vessel occlusion on the angiogram means the clot may be retrievable at the stroke centre.
 */
public class StrokeImaging extends StrokeServiceBase {

    /** Whether the imaging request is a stroke code from a receiving hospital. */
    public boolean isStrokeCode(String request) {
        return Boolean.parseBoolean(textValue(request, "stroke_code", "false"));
    }

    @SuppressWarnings("unchecked")
    public String ctStrokeProtocol(String triageResults) {
        JSONObject strokeCase = strokeCase(triageResults);
        boolean haemorrhage = flag(strokeCase, "haemorrhage");
        boolean lvo = !haemorrhage && flag(strokeCase, "lvo");
        JSONObject findings = new JSONObject();
        findings.put("protocol", "CT_STROKE: non-contrast CT brain and CT angiography");
        findings.put("haemorrhage", haemorrhage);
        findings.put("large_vessel_occlusion", lvo);
        findings.put("occlusion_site", lvo ? text(strokeCase, "occlusion_site", "left M1 middle cerebral artery") : "none");
        findings.put("impression", haemorrhage ? "Intracerebral haemorrhage"
            : lvo ? "Acute ischaemic stroke with large-vessel occlusion" : "No haemorrhage; no large-vessel occlusion");
        return strokeResult("radiologyResults", strokeCase, workflowId(triageResults), findings, null);
    }
}
