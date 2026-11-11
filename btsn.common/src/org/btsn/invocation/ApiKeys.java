package org.btsn.invocation;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The API keys a call mapping names as ${env:NAME}. A key is the environment variable NAME when it is set, else the
 * entry NAME in this computer's key file, ~/.rpso/api-keys.properties (or the file named by -Drpso.keys.file). The
 * editor's API keys... writes that file, so a key is kept on the machine and never in a deployment or the repository.
 */
public final class ApiKeys {
    private ApiKeys() {}

    /** The key file of this computer. */
    public static File file() {
        String named = System.getProperty("rpso.keys.file");
        return named != null && !named.isEmpty() ? new File(named) : new File(System.getProperty("user.home"), ".rpso/api-keys.properties");
    }

    /** The key NAME: the environment variable, else the key file's entry; null when neither has it. */
    public static String get(String name) {
        String value = System.getenv(name);
        if (value != null && !value.isEmpty()) return value;
        value = stored().get(name);
        return value == null || value.isEmpty() ? null : value;
    }

    /** True when the key comes from an environment variable, which the key file cannot change. */
    public static boolean fromEnvironment(String name) {
        String value = System.getenv(name);
        return value != null && !value.isEmpty();
    }

    /** The keys in the key file, by name. */
    public static Map<String, String> stored() {
        Map<String, String> keys = new TreeMap<>();
        File file = file();
        if (!file.isFile()) return keys;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) { p.load(r); }
        catch (Exception e) { return keys; }
        for (String name : p.stringPropertyNames()) keys.put(name, p.getProperty(name));
        return keys;
    }

    /** Writes the keys given (a blank value removes the key), keeping the others in the file. */
    public static void store(Map<String, String> changes) throws java.io.IOException {
        Map<String, String> keys = stored();
        for (Map.Entry<String, String> e : changes.entrySet()) {
            if (e.getValue() == null || e.getValue().trim().isEmpty()) keys.remove(e.getKey()); else keys.put(e.getKey(), e.getValue().trim());
        }
        File file = file();
        Files.createDirectories(file.getParentFile().toPath());
        Properties p = new Properties();
        p.putAll(keys);
        try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) { p.store(w, "RPSO API keys for this computer. Do not share or commit this file."); }
        file.setReadable(false, false); file.setReadable(true, true);
        file.setWritable(false, false); file.setWritable(true, true);
    }

    /** Why a key is missing, and how to give it. */
    public static String missing(String name) {
        return "the API key " + name + " is not set on this computer: enter it in Deploy with API keys..., or set the environment variable " + name;
    }
}
