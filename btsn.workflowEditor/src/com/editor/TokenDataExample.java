package com.editor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * ExampleTokenDataFile.json in TokenFormats: a token data file to copy, kept there and read-only so it is never
 * overwritten. Written again if it is missing.
 */
final class TokenDataExample {
    static final String NAME = "ExampleTokenDataFile.json";
    static final String TEXT = "{\n"
        + "  \"_readMe\": [\n"
        + "    \"An example token data file. Do not change this one: copy it to a new name in this folder (for example MyService.json),\",\n"
        + "    \"change the fields to the ones your service reads, and choose MyService.json as the event generator's Token Data File Name.\",\n"
        + "    \"Every token gets tokenStructure.data. Token n then also gets the dataVariants entry named at position n of caseMix (wrapping round),\",\n"
        + "    \"so each run sends the same mix. In text, ${n} becomes the token's number (1, 2, ...). Numbers and true/false stay as written.\",\n"
        + "    \"A service mapping reads these fields as ${data.orderId}, ${data.amount}, and so on.\"\n"
        + "  ],\n"
        + "  \"description\": \"An example: an order with a number, a customer, a currency, and an amount and card that vary by the kind of order.\",\n"
        + "  \"tokenStructure\": {\n"
        + "    \"data\": {\n"
        + "      \"orderId\": \"ORD-${n}\",\n"
        + "      \"customer\": \"C-${n}\",\n"
        + "      \"currency\": \"AUD\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"dataVariants\": {\n"
        + "    \"small\": { \"amount\": 25.00,  \"card\": \"visa-4242\" },\n"
        + "    \"large\": { \"amount\": 480.00, \"card\": \"mastercard-5100\" }\n"
        + "  },\n"
        + "  \"caseMix\": [\"small\", \"small\", \"large\"]\n"
        + "}\n";

    private TokenDataExample() {}

    /** The example as the start of a new file of the designer's own: the same fields, with a read-me for a copy. */
    static String copyText() {
        int start = TEXT.indexOf("  \"_readMe\""), end = TEXT.indexOf("  ],\n", start) + 5;
        return TEXT.substring(0, start)
            + "  \"_readMe\": \"Your token data file, started from ExampleTokenDataFile.json. Every token gets tokenStructure.data; token n also gets the dataVariants entry at position n of caseMix. ${n} is the token's number. A service mapping reads the fields as ${data.name}.\",\n"
            + TEXT.substring(end);
    }

    /** Writes the example into the folder if it is missing, and makes it read-only. */
    static void ensure(File folder) {
        File file = new File(folder, NAME);
        try {
            if (!file.isFile()) Files.write(file.toPath(), TEXT.getBytes(StandardCharsets.UTF_8));
            if (file.canWrite()) file.setReadOnly();
        } catch (Exception ignored) { }   // a folder the editor cannot write to: the example is simply not offered
    }
}
