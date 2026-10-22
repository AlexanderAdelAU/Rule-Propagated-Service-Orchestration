package com.editor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;

/**
 * Adds one service entry to a domain catalogue, or rewrites an entry that is still a draft, while designing.
 * The rest of the file is left exactly as it was: entries are found by their braces and only that span is
 * replaced, so hand-written layout and every other service survive. Finished (active) entries are never
 * rewritten here, because other processes may already use their contract.
 */
public final class CatalogueWriter {
    private CatalogueWriter() { }

    /** One catalogue entry as the designer describes it. Blank fields are left out of the file. */
    public static final class Entry {
        public String service = "", operation = "", description = "", returns = "", returnAttribute = "", implementationClass = "";
        public final List<String> inputs = new ArrayList<>(), routes = new ArrayList<>();
        public boolean draft = true;

        /** The entry as it is written: fields in the catalogue's usual order. */
        String render(String indent) {
            String inner = indent + "  ";
            List<String> fields = new ArrayList<>();
            fields.add(field(inner, "service", service));
            if (!implementationClass.isEmpty()) fields.add(field(inner, "implementationClass", implementationClass));
            fields.add(field(inner, "operation", operation));
            if (!description.isEmpty()) fields.add(field(inner, "description", description));
            if (!returns.isEmpty()) fields.add(field(inner, "returns", returns));
            if (!inputs.isEmpty()) fields.add(inner + "\"inputs\": " + list(inputs));
            if (!returnAttribute.isEmpty()) fields.add(field(inner, "returnAttribute", returnAttribute));
            if (!routes.isEmpty()) fields.add(inner + "\"routes\": " + list(routes));
            fields.add(field(inner, "status", draft ? ServiceRegistry.DRAFT : "active"));
            return indent + "{\n" + String.join(",\n", fields) + "\n" + indent + "}";
        }
        private static String field(String indent, String name, String value) { return indent + "\"" + name + "\": \"" + JSONValue.escape(value) + "\""; }
        private static String list(List<String> values) {
            List<String> quoted = new ArrayList<>();
            for (String value : values) quoted.add("\"" + JSONValue.escape(value) + "\"");
            return "[" + String.join(", ", quoted) + "]";
        }
    }

    /** Adds a new entry at the end of the catalogue's services. Its service and operation must not exist yet. */
    public static void add(File catalogue, Entry entry) throws Exception {
        String text = read(catalogue);
        Services services = services(text);
        for (Span span : services.entries)
            if (entry.service.equals(span.service) && entry.operation.equals(span.operation))
                throw new IOException(entry.service + "." + entry.operation + " is already in " + catalogue.getName() + ".");
        String indent = services.entries.isEmpty() ? "    " : services.entries.get(services.entries.size() - 1).indent;
        String rendered = entry.render(indent);
        String updated;
        if (services.entries.isEmpty()) {
            updated = text.substring(0, services.open + 1) + "\n" + rendered + "\n  " + text.substring(services.close);
        } else {
            int end = services.entries.get(services.entries.size() - 1).end;
            updated = text.substring(0, end) + ",\n" + rendered + text.substring(end);
        }
        write(catalogue, updated);
    }

    /** A domain name: the catalogue's folder and the Java package of its services' starting code. */
    public static boolean validDomain(String domain) {
        return domain != null && domain.matches("[a-z][a-z0-9_]*") && javax.lang.model.SourceVersion.isName(domain);
    }
    /** claims -> Claims.json, home_loans -> HomeLoans.json */
    public static String catalogueName(String domain) {
        StringBuilder name = new StringBuilder();
        for (String part : domain.split("_")) if (!part.isEmpty()) name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        return name + ".json";
    }

    /**
     * A new domain's empty catalogue, BusinessServiceDefinitions/<domain>/<Name>.json, registered in
     * btsn.services/packaging.json so the build packages the Java services that will be added to it.
     */
    public static File create(File common, String domain) throws Exception {
        if (!validDomain(domain))
            throw new IOException("A domain name is lower case letters, digits and _, starting with a letter (it is also the Java package of its services): " + domain);
        File folder = new File(common, "BusinessServiceDefinitions/" + domain);
        File catalogue = new File(folder, catalogueName(domain));
        File[] existing = folder.listFiles((dir, name) -> name.endsWith(".json"));
        if (existing != null && existing.length > 0)
            throw new IOException("The " + domain + " domain already has a catalogue, " + existing[0].getName() + ": choose it from the list.");
        for (File other : ServiceRegistry.domainCatalogues(common))
            if (other.getName().equals(catalogue.getName()))
                throw new IOException(catalogue.getName() + " is already the catalogue of the " + other.getParentFile().getName() + " domain; choose another domain name.");
        String title = catalogue.getName().replaceFirst("\\.json$", "");
        String text = "{\n"
            + "  \"domain\": \"" + title + "\",\n"
            + "  \"purpose\": \"Services of the " + domain + " processes, added while designing. Physical placement is defined separately by the service deployment.\",\n"
            + "  \"services\": [\n"
            + "  ]\n"
            + "}\n";
        Files.createDirectories(folder.toPath());
        write(catalogue, text);
        registerForPackaging(common, catalogue, domain);
        return catalogue;
    }

    /**
     * Lists the catalogue in btsn.services/packaging.json (catalogues and catalogueDomains), keeping the file's layout.
     * Returns false when there is no packaging.json beside btsn.common, or the catalogue is already listed.
     */
    public static boolean registerForPackaging(File common, File catalogue, String domain) throws Exception {
        File packaging = new File(common.getParentFile(), "btsn.services/packaging.json");
        if (!packaging.isFile()) return false;
        String text = read(packaging);
        JSONObject data = (JSONObject)new JSONParser().parse(text);
        String path = "../btsn.common/BusinessServiceDefinitions/" + domain + "/" + catalogue.getName();
        Object listed = data.get("catalogues"), domains = data.get("catalogueDomains");
        if (!(listed instanceof JSONArray) || !(domains instanceof JSONObject)) throw new IOException("packaging.json has no catalogues or catalogueDomains.");
        if (((JSONArray)listed).contains(path)) return false;
        Object mapped = ((JSONObject)domains).get(catalogue.getName());
        if (mapped != null && !domain.equals(mapped)) throw new IOException(catalogue.getName() + " is already packaged for the " + mapped + " domain.");
        text = append(text, "\"catalogues\"", '[', ']', "\"" + path + "\"");
        if (mapped == null) text = append(text, "\"catalogueDomains\"", '{', '}', "\"" + catalogue.getName() + "\": \"" + domain + "\"");
        write(packaging, text);
        return true;
    }
    /**
     * The catalogues btsn.services/packaging.json lists that no longer exist (deleted or moved after they were listed),
     * as written there. Packaging reads every listed catalogue, so one of these stops every build.
     */
    public static List<String> missingFromPackaging(File common) {
        List<String> missing = new ArrayList<>();
        File services = common == null ? null : new File(common.getParentFile(), "btsn.services");
        File packaging = services == null ? null : new File(services, "packaging.json");
        if (packaging == null || !packaging.isFile()) return missing;
        try {
            Object listed = ((JSONObject)new JSONParser().parse(read(packaging))).get("catalogues");
            if (listed instanceof JSONArray)
                for (Object path : (JSONArray)listed) if (!new File(services, path.toString()).isFile()) missing.add(path.toString());
        } catch (Exception unreadable) { }
        return missing;
    }

    /** Removes a listed catalogue from packaging.json (catalogues and its catalogueDomains entry), keeping the file's layout. */
    public static boolean unregisterFromPackaging(File common, String path) throws Exception {
        File packaging = new File(common.getParentFile(), "btsn.services/packaging.json");
        if (!packaging.isFile()) return false;
        String text = read(packaging);
        String after = remove(text, "\"catalogues\"", '[', "\"" + path + "\"");
        if (after.equals(text)) return false;
        String name = new File(path).getName();
        boolean stillListed = false;
        for (Object other : (JSONArray)((JSONObject)new JSONParser().parse(after)).get("catalogues"))
            if (new File(other.toString()).getName().equals(name)) stillListed = true;
        if (!stillListed) after = remove(after, "\"catalogueDomains\"", '{', "\"" + name + "\"");
        new JSONParser().parse(after);   // never leave packaging.json unreadable
        write(packaging, after);
        return true;
    }
    /** Removes the entry starting with start (and its value, in an object) from the list after key, with its comma. */
    private static String remove(String text, String key, char open, String start) throws IOException {
        int at = text.indexOf(key), from = at < 0 ? -1 : text.indexOf(open, at);
        int entry = from < 0 ? -1 : text.indexOf(start, from);
        if (entry < 0) return text;
        int end = entry + start.length();
        if (open == '{') { end = text.indexOf('"', text.indexOf(':', end)); end = text.indexOf('"', end + 1) + 1; }
        int before = entry - 1;
        while (before > from && Character.isWhitespace(text.charAt(before))) before--;
        if (text.charAt(before) == ',') return text.substring(0, before) + text.substring(end);
        int next = end;
        while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
        if (next < text.length() && text.charAt(next) == ',') {
            next++;
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
            return text.substring(0, entry) + text.substring(next);
        }
        return text.substring(0, entry) + text.substring(end);
    }

    /** Adds an entry at the end of the array or object that follows key, indented like the entries before it. */
    private static String append(String text, String key, char open, char close, String entry) throws IOException {
        int at = text.indexOf(key);
        int start = at < 0 ? -1 : text.indexOf(open, at), end = start < 0 ? -1 : text.indexOf(close, start);
        if (end < 0) throw new IOException("packaging.json: " + key + " not found.");
        String inside = text.substring(start + 1, end);
        int last = inside.lastIndexOf('"');
        if (last < 0) return text.substring(0, start + 1) + "\n    " + entry + "\n  " + text.substring(end);
        int lineStart = inside.lastIndexOf('\n', last) + 1, indentEnd = lineStart;
        while (indentEnd < inside.length() && (inside.charAt(indentEnd) == ' ' || inside.charAt(indentEnd) == '\t')) indentEnd++;
        int insertAt = start + 1 + last + 1;
        return text.substring(0, insertAt) + ",\n" + inside.substring(lineStart, indentEnd) + entry + text.substring(insertAt);
    }

    /** Rewrites the draft entry service.operation as entry, which may also rename it or make it active. */
    public static void updateDraft(File catalogue, String service, String operation, Entry entry) throws Exception {
        update(catalogue, service, operation, entry, false);
    }

    /**
     * Rewrites the entry service.operation as entry. A finished (active) entry is rewritten only when allowActive:
     * the editor allows it when no other process uses the service, and otherwise keeps its contract fixed.
     */
    public static void update(File catalogue, String service, String operation, Entry entry, boolean allowActive) throws Exception {
        String text = read(catalogue);
        Services services = services(text);
        Span target = null;
        boolean clash = false;
        for (Span span : services.entries) {
            if (service.equals(span.service) && operation.equals(span.operation)) target = span;
            else if (entry.service.equals(span.service) && entry.operation.equals(span.operation)) clash = true;
        }
        if (target == null) throw new IOException(service + "." + operation + " is not in " + catalogue.getName() + ".");
        if (!allowActive && !ServiceRegistry.DRAFT.equals(target.status))
            throw new IOException(service + " is finished (active), and other processes may use it; change it in " + catalogue.getName() + " itself.");
        if (clash) throw new IOException(entry.service + "." + entry.operation + " is already in " + catalogue.getName() + ".");
        // The file already has the indent before the entry's opening brace.
        write(catalogue, text.substring(0, target.start) + entry.render(target.indent).substring(target.indent.length())
            + text.substring(target.end));
    }

    // ------------------------------------------------------------------ locating entries

    private static final class Span { int start, end; String indent, service, operation, status; }
    private static final class Services { int open, close; final List<Span> entries = new ArrayList<>(); }

    /** The services array and the span of each entry in it, found by matching braces outside strings. */
    private static Services services(String text) throws Exception {
        if (!(new JSONParser().parse(text) instanceof JSONObject)) throw new IOException("A catalogue is a JSON object.");
        int key = text.indexOf("\"services\"");
        if (key < 0) throw new IOException("The catalogue has no services list.");
        Services result = new Services();
        result.open = text.indexOf('[', key);
        int depth = 0, start = -1;
        boolean inString = false;
        for (int i = result.open + 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) { if (ch == '\\') i++; else if (ch == '"') inString = false; continue; }
            if (ch == '"') inString = true;
            else if (ch == '{') { if (depth++ == 0) start = i; }
            else if (ch == '}') {
                if (--depth == 0) {
                    Span span = new Span(); span.start = start; span.end = i + 1;
                    int lineStart = text.lastIndexOf('\n', start) + 1;
                    span.indent = text.substring(lineStart, start).matches("[ \\t]*") ? text.substring(lineStart, start) : "    ";
                    JSONObject object = (JSONObject)new JSONParser().parse(text.substring(start, i + 1));
                    span.service = string(object.get("service")); span.operation = string(object.get("operation")); span.status = string(object.get("status"));
                    result.entries.add(span);
                }
            } else if (ch == ']' && depth == 0) { result.close = i; return result; }
        }
        throw new IOException("The catalogue's services list is not closed.");
    }
    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static String read(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static void write(File file, String text) throws Exception {
        new JSONParser().parse(text);   // never leave a catalogue that does not parse
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /** The entry for an existing draft contract, for the form to start from. */
    public static Entry from(ServiceRegistry.Contract c) {
        Entry e = new Entry();
        e.service = c.service; e.operation = c.operation; e.description = c.description; e.returns = c.returns;
        e.returnAttribute = c.output; e.implementationClass = c.implementationClass; e.draft = c.draft;
        e.inputs.addAll(c.inputs); e.routes.addAll(c.routes);
        return e;
    }
}
