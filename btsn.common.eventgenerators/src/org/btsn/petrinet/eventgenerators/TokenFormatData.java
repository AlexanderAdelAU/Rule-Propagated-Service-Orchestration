package org.btsn.petrinet.eventgenerators;

import java.io.File;
import java.io.FileReader;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * The data a token carries, from a token format: TokenFormats/&lt;name&gt;.json (egN.format.service).
 *
 * The format's tokenStructure.data is every token's data. When the format has a caseMix, token n of a version takes
 * the dataVariants entry at position n of that list (wrapping round), so each run brings the same mix. Then any -data
 * pairs (egN.data) are laid on top. In text values, ${n} is the token's number in its version (1, 2, ...) and
 * ${sequenceId} its token id. Numbers and true/false stay as they are written.
 */
final class TokenFormatData {

    private static final String[] SEARCH = {"TokenFormats", "../btsn.common.eventgenerators/TokenFormats"};
    private static String loadedName;
    private static JSONObject loaded;

    private TokenFormatData() {}

    /** The token's data: the format's (if any), then the name=value pairs. */
    @SuppressWarnings("unchecked")
    static JSONObject build(String formatName, int sequenceId, String pairs) {
        JSONObject data = new JSONObject();
        JSONObject format = formatName == null || formatName.isEmpty() ? null : load(formatName);
        int n = (sequenceId % 1_000_000) / 10_000 + 1;
        if (format != null) {
            JSONObject structure = (JSONObject) format.get("tokenStructure");
            Object defaults = structure == null ? null : structure.get("data");
            if (defaults instanceof JSONObject) copy((JSONObject) defaults, data, n, sequenceId);
            JSONArray mix = (JSONArray) format.get("caseMix");
            JSONObject variants = (JSONObject) format.get("dataVariants");
            if (mix != null && !mix.isEmpty() && variants != null) {
                Object variant = variants.get(String.valueOf(mix.get((n - 1) % mix.size())));
                if (variant instanceof JSONObject) copy((JSONObject) variant, data, n, sequenceId);
            }
        }
        if (pairs != null && !pairs.isEmpty()) {
            for (String pair : pairs.split(",")) {
                String[] kv = pair.split("=");
                if (kv.length == 2) data.put(kv[0].trim(), fill(kv[1].trim(), n, sequenceId));
            }
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private static void copy(JSONObject from, JSONObject to, int n, int sequenceId) {
        for (Object key : from.keySet()) {
            if (String.valueOf(key).startsWith("_")) continue;   // a comment
            Object value = from.get(key);
            to.put(key, value instanceof String ? fill((String) value, n, sequenceId) : value);
        }
    }

    private static String fill(String value, int n, int sequenceId) {
        return value.replace("${n}", String.valueOf(n)).replace("${sequenceId}", String.valueOf(sequenceId));
    }

    /** TokenFormats/&lt;name&gt;.json, from the generator's folder or its sibling; null (with a warning) if there is none. */
    private static JSONObject load(String name) {
        if (name.equals(loadedName)) return loaded;
        loadedName = name;
        loaded = null;
        for (String folder : SEARCH) {
            File file = new File(folder, name + ".json");
            if (!file.isFile()) continue;
            try (FileReader reader = new FileReader(file)) {
                loaded = (JSONObject) new JSONParser().parse(reader);
                System.out.println("Token format: " + file.getPath());
            } catch (Exception e) {
                System.out.println("WARNING: token format " + file.getPath() + " could not be read: " + e.getMessage());
            }
            return loaded;
        }
        System.out.println("WARNING: no token format " + name + ".json in " + String.join(" or ", SEARCH) + "; tokens carry no format data");
        return null;
    }
}
