package org.btsn.deployment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Proof that Monitor has stored what a host's collector sent.
 *
 * A run's collection asks every host's collector for its records, once for each rule version collected
 * (query.version: v001, or v001,v002 ...). Each reply names its place and version, and Monitor writes a receipt
 * (P3_Place.v001) once it has stored that reply; a collector that reported an error leaves P3_Place.failed instead.
 * The launcher clears the receipts before it sends the requests, and the collection is finished when there is a
 * receipt for every host and version: a slow collector cannot be mistaken for a finished one.
 */
public final class CollectionReceipts {
    /** The folder, in Monitor's working directory, that holds the receipts. */
    public static final String FOLDER = "CollectionReceipts";
    private static final String FAILED = "failed";

    private CollectionReceipts() { }

    /** Removes every receipt (before a collection's requests are sent). */
    public static void clear(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files::iterator) Files.deleteIfExists(file);
        }
    }

    /** Monitor has stored place's reply for version; detail says what (record counts). */
    public static void stored(Path folder, String place, String version, String detail) throws IOException {
        write(folder, place + "." + version, detail);
    }

    /** place's collector reported an error instead of its records. */
    public static void failed(Path folder, String place, String reason) throws IOException {
        write(folder, place + "." + FAILED, reason);
    }

    /**
     * The hosts (P1, P3, ...) whose replies Monitor has not yet stored, each with the versions still missing,
     * e.g. "P3 (v002)". A host whose collector reported an error counts as answered: see failures.
     */
    public static List<String> missing(Path folder, List<String> hosts, List<String> versions) {
        List<String> missing = new ArrayList<>();
        for (String host : hosts) {
            String place = place(host);
            if (Files.exists(folder.resolve(place + "." + FAILED))) continue;
            List<String> versionsMissing = new ArrayList<>();
            for (String version : versions)
                if (!Files.exists(folder.resolve(place + "." + version))) versionsMissing.add(version);
            if (!versionsMissing.isEmpty()) missing.add(host + " (" + String.join(", ", versionsMissing) + ")");
        }
        return missing;
    }

    /** The hosts whose collector reported an error, each with its reason. */
    public static List<String> failures(Path folder, List<String> hosts) throws IOException {
        List<String> failures = new ArrayList<>();
        for (String host : hosts) {
            Path file = folder.resolve(place(host) + "." + FAILED);
            if (Files.exists(file)) failures.add(host + ": " + Files.readString(file, StandardCharsets.UTF_8).trim());
        }
        return failures;
    }

    /** P3 -> P3_Place (the place a host's collector reports). */
    static String place(String host) {
        return host.endsWith("_Place") ? host : host + "_Place";
    }

    private static void write(Path folder, String name, String text) throws IOException {
        Files.createDirectories(folder);
        Path temporary = folder.resolve(name + ".writing");
        Files.writeString(temporary, text == null ? "" : text, StandardCharsets.UTF_8);
        Files.move(temporary, folder.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                   java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
