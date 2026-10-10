package com.editor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import javax.swing.*;
import org.json.simple.*;
import org.json.simple.parser.JSONParser;

/** Catalogue-backed editor choices. A selected deployment fixes the process contract. */
public final class ServiceRegistry {
    public static final class Contract {
        public String service, operation, output, resultType;
        public final List<String> inputs = new ArrayList<>();
    }
    public static final class Endpoint {
        public String service, operation, instance, output, adapter, node;
        public final List<String> inputs = new ArrayList<>();
        public final List<ServiceArgument> arguments = new ArrayList<>();
    }
    private final Map<String, Contract> contracts = new LinkedHashMap<>();
    private final List<Endpoint> endpoints = new ArrayList<>();
    private File common, deployment, catalogue;
    private static final String NO_CATALOGUE = "Choose a service catalogue for this process.";
    private String problem = NO_CATALOGUE;
    public String problem() { return problem; }
    public File common() { return common; }
    public String reference() { return deployment == null ? "" : common.toPath().relativize(deployment.toPath()).toString().replace(File.separatorChar, '/'); }
    public String selectionLabel() { return deployment != null ? "Deployment: " + deployment.getName() : catalogue != null ? "Catalogue: " + catalogue.getName() : problem; }
    public String description() { return deployment != null ? "Deployment: " + reference() + " | Catalogue: " + common.toPath().relativize(catalogue.toPath()).toString().replace(File.separatorChar, '/') : selectionLabel(); }
    public static File findCommon(File anchor) {
        for (File cursor = anchor == null ? new File(System.getProperty("user.dir")) : anchor; cursor != null; cursor = cursor.getParentFile()) {
            if (new File(cursor, "BusinessServiceDefinitions").isDirectory()) return cursor;
            File candidate = new File(cursor, "btsn.common");
            if (new File(candidate, "BusinessServiceDefinitions").isDirectory()) return candidate;
        }
        try {
            File location = new File(ServiceRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (anchor == null && location.exists()) return findCommon(location.isFile() ? location.getParentFile() : location);
        } catch (Exception ignored) { }
        return null;
    }
    public void loadReference(String reference, File anchor) {
        reset();
        if (reference == null || reference.isEmpty()) return;
        File root = findCommon(anchor);
        if (root == null) { problem = "Cannot locate btsn.common; choose the service deployment explicitly."; return; }
        try { loadDeployment(new File(root, reference)); }
        catch (Exception ex) { problem = ex.getMessage(); }
    }
    private void reset() { contracts.clear(); endpoints.clear(); deployment = null; catalogue = null; common = null; problem = NO_CATALOGUE; }
    public boolean hasDeployment() { return deployment != null; }
    public File deploymentFile() { return deployment; }
    public File catalogueFile() { return catalogue; }

    /**
     * Load what a process refers to: its service deployment once it has been deployed, otherwise its
     * catalogue, otherwise the single catalogue of the domain folder the process is saved in.
     */
    public void loadProcessReferences(String deploymentReference, String catalogueReference, File anchor) {
        if (deploymentReference != null && !deploymentReference.isEmpty()) { loadReference(deploymentReference, anchor); return; }
        reset();
        File root = findCommon(anchor);
        if (root == null) return;
        String reference = catalogueReference == null || catalogueReference.isEmpty() ? domainCatalogue(root, anchor) : catalogueReference;
        if (reference.isEmpty()) return;
        try { loadCatalogue(new File(root, reference)); }
        catch (Exception ex) { problem = ex.getMessage(); }
    }
    /** Keep the catalogue but forget the deployment, e.g. when a new process is started. */
    public void dropDeployment() {
        if (deployment == null) return;
        File source = catalogue;
        try { loadCatalogue(source); } catch (Exception ex) { reset(); }
    }
    /** BusinessServiceDefinitions/<domain>/X.json when a process lies under ProcessDefinitionFolder/<domain> and that folder has one catalogue. */
    static String domainCatalogue(File root, File anchor) {
        if (anchor == null) return "";
        try {
            java.nio.file.Path processes = new File(root, "ProcessDefinitionFolder").getCanonicalFile().toPath();
            java.nio.file.Path location = anchor.getCanonicalFile().toPath();
            if (!location.startsWith(processes) || location.equals(processes)) return "";
            String domain = processes.relativize(location).getName(0).toString();
            File[] candidates = new File(root, "BusinessServiceDefinitions/" + domain).listFiles((dir, name) -> name.endsWith(".json"));
            if (candidates == null || candidates.length != 1) return "";
            return "BusinessServiceDefinitions/" + domain + "/" + candidates[0].getName();
        } catch (IOException ex) { return ""; }
    }
    public void loadDeployment(File selected) throws Exception {
        reset();
        File root = findCommon(selected.getAbsoluteFile().getParentFile());
        if (root == null) throw new IOException("The deployment must belong to a project with btsn.common.");
        selected = selected.getCanonicalFile(); root = root.getCanonicalFile();
        JSONObject data = read(selected);
        if (!"ServiceDeployment".equals(data.get("definitionType"))) throw new IOException("Select a ServiceDeployment JSON definition.");
        Set<String> catalogues = new LinkedHashSet<>();
        List<File> profiles = new ArrayList<>();
        profiles.add(new File(root, "BusinessServiceDefinitions/Deployment.json"));
        collect(new File(root.getParentFile(), "btsn.services/deployments"), profiles);
        for (File profile : profiles) {
            if (!profile.isFile()) continue;
            JSONObject config = read(profile);
            if (config.get("serviceDeployment") != null && selected.equals(new File(root, text(config, "serviceDeployment")).getCanonicalFile()))
                catalogues.add(text(config, "catalog"));
        }
        if (data.get("catalog") != null) {
            String declared = text(data, "catalog");
            if (!catalogues.isEmpty() && !catalogues.contains(declared)) throw new IOException("Deployment catalogue conflicts with its Deployment.json profile.");
            catalogues.add(declared);
        }
        if (catalogues.size() != 1) throw new IOException("Deployment must have exactly one catalogue association in a Deployment.json profile; found " + catalogues.size() + ".");
        File source = new File(root, catalogues.iterator().next());
        loadCatalogue(source);
        common = root; deployment = selected; catalogue = source;
        for (Object item : array(data, "capabilities")) {
            JSONObject placement = (JSONObject)item;
            Endpoint e = new Endpoint(); e.service = text(placement, "service"); e.operation = text(placement, "operation"); e.instance = text(placement, "instance");
            if (e.instance.isEmpty()) e.instance = e.service;
            e.output = text(placement, "returnAttribute"); e.adapter = text(placement, "invocationAdapter");
            e.node = text(placement, "node");
            for (Object input : array(placement, "arguments")) {
                JSONObject parameter = (JSONObject)input;
                String name = text(parameter, "name"); e.inputs.add(name);
                e.arguments.add(new ServiceArgument(name, text(parameter, "type"), text(parameter, "value"), Boolean.TRUE.equals(parameter.get("required"))));
            }
            endpoints.add(e);
        }
        problem = null;
    }
    public String catalogueReference() { return catalogue == null || common == null ? "" : common.toPath().relativize(catalogue.toPath()).toString().replace(File.separatorChar, '/'); }
    public void loadCatalogue(File source) throws Exception {
        reset();
        File root = findCommon(source.getAbsoluteFile().getParentFile());
        if (root == null) throw new IOException("Select a catalogue in btsn.common/BusinessServiceDefinitions.");
        Map<String, Contract> loaded = new LinkedHashMap<>();
        for (Object item : array(read(source), "services")) {
            JSONObject definition = (JSONObject)item;
            if (!"active".equals(text(definition, "status"))) continue;
            Contract c = new Contract(); c.service = text(definition, "service"); c.operation = text(definition, "operation"); c.output = text(definition, "returnAttribute"); c.resultType = text(definition, "resultType");
            for (Object input : array(definition, "inputs")) c.inputs.add(input.toString());
            if (loaded.put(key(c.service, c.operation), c) != null) throw new IOException("Duplicate catalogue operation: " + c.service + "." + c.operation);
        }
        if (loaded.isEmpty()) throw new IOException("Catalogue has no active service operations.");
        contracts.putAll(loaded); common = root.getCanonicalFile(); catalogue = source.getCanonicalFile(); problem = null;
    }
    private static void collect(File dir, List<File> files) {
        File[] children = dir.listFiles(); if (children == null) return;
        Arrays.sort(children);
        for (File child : children) if (child.isDirectory()) collect(child, files); else if (child.getName().endsWith(".json")) files.add(child);
    }
    private static JSONObject read(File file) throws Exception { return (JSONObject)new JSONParser().parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
    private static JSONArray array(JSONObject data, String field) { Object value = data.get(field); return value instanceof JSONArray ? (JSONArray)value : new JSONArray(); }
    private static String text(JSONObject data, String field) { Object value = data.get(field); return value == null ? "" : value.toString(); }
    private static String key(String service, String op) { return service + "\u0000" + op; }
    public List<String> services() { Set<String> values = new LinkedHashSet<>(); for (Contract c : contracts.values()) values.add(c.service); return new ArrayList<>(values); }
    public List<String> operations(String service) { List<String> values = new ArrayList<>(); for (Contract c : contracts.values()) if (c.service.equals(service)) values.add(c.operation); return values; }
    public List<String> instances(String service, String op) { Set<String> values = new LinkedHashSet<>(); for (Endpoint e : endpoints) if (e.service.equals(service) && (op == null || op.equals(e.operation))) values.add(e.instance); return new ArrayList<>(values); }
    public Contract contract(String service, String op) { return contracts.get(key(service, op)); }
    /** Infrastructure node (e.g. "P1") that hosts a deployment instance, or "" when unknown or ambiguous. */
    public String nodeForInstance(String instance) {
        String node = "";
        for (Endpoint e : endpoints) {
            if (!e.instance.equals(instance) || e.node == null || e.node.isEmpty()) continue;
            if (!node.isEmpty() && !node.equals(e.node)) return "";
            node = e.node;
        }
        return node;
    }
    public Endpoint endpoint(String service, String op, String instance) {
        Endpoint result = null;
        for (Endpoint e : endpoints) if (e.service.equals(service) && e.operation.equals(op) && e.instance.equals(instance)) { if (result != null) return null; result = e; }
        return result;
    }
    public String catalogueAssociationError(File selected) {
        if (selected == null || catalogue == null) return null;
        try {
            File root = findCommon(selected.getAbsoluteFile().getParentFile());
            if (root == null) return "Cannot resolve the deployment project.";
            List<File> profiles = new ArrayList<>();
            profiles.add(new File(root, "BusinessServiceDefinitions/Deployment.json"));
            collect(new File(root.getParentFile(), "btsn.services/deployments"), profiles);
            for (File profile : profiles) if (profile.isFile()) {
                JSONObject config = read(profile);
                if (config.get("serviceDeployment") != null && selected.getCanonicalFile().equals(new File(root, text(config, "serviceDeployment")).getCanonicalFile()) &&
                    !catalogue.getCanonicalFile().equals(new File(root, text(config, "catalog")).getCanonicalFile()))
                    return "Selected catalogue conflicts with " + profile.getName() + "; update the deployment profile association first.";
            }
        } catch (Exception ex) { return ex.getMessage(); }
        return null;
    }

    public List<String> validateEndpoint(String service, String op, String output, List<String> inputs, String adapter) {
        List<String> errors = new ArrayList<>();
        if (problem != null) { errors.add(problem); return errors; }
        Contract c = contract(service, op);
        if (c == null) { errors.add("Undefined catalogue operation: " + service + "." + op); return errors; }
        if (adapter.isEmpty()) {
            if ("boolean".equals(c.resultType)) errors.add("Choose boolean-token explicitly for this Boolean service.");
            if (!c.inputs.equals(inputs)) errors.add("Inputs must match catalogue: " + c.inputs);
            if (!c.output.equals(output)) errors.add("Result must match catalogue: " + c.output);
        } else if (!"boolean-token".equals(adapter) || !"boolean".equals(c.resultType) || !c.inputs.equals(Arrays.asList("data")) || !"decision".equals(c.output)) {
            errors.add("boolean-token requires a declared Boolean data -> decision contract.");
        }
        if (output.isEmpty() || inputs.contains("") || new HashSet<>(inputs).size() != inputs.size()) errors.add("Result and input aliases must be nonempty and input aliases unique.");
        return errors;
    }
    public List<String> validatePlace(ProcessElement place) {
        List<String> errors = new ArrayList<>();
        if (problem != null) { errors.add(problem); return errors; }
        if (!services().contains(place.getService())) errors.add("Undefined service: " + place.getService());
        if (place.getServiceOperations().isEmpty()) errors.add("Choose a declared operation.");
        Set<String> used = new HashSet<>();
        for (ServiceOperation operation : place.getServiceOperations()) {
            if (!used.add(operation.getName())) errors.add("Duplicate operation: " + operation.getName());
            List<String> names = new ArrayList<>(); for (ServiceArgument input : operation.getArguments()) names.add(input.getName());
            Endpoint e = endpoint(place.getService(), operation.getName(), identity(place));
            if (e != null) {
                errors.addAll(validateEndpoint(e.service, e.operation, e.output, e.inputs, e.adapter));
                if (!e.inputs.equals(names)) errors.add("Process inputs differ from deployment " + e.instance + ": expected " + e.inputs);
                if (!e.output.equals(operation.getReturnAttribute())) errors.add("Process result differs from deployment " + e.instance + ": expected " + e.output);
                continue;
            }
            // Not deployed yet: the catalogue fixes the contract. Deployment is a later, separate step.
            Contract c = contract(place.getService(), operation.getName());
            if (c == null) { errors.add("Undefined catalogue operation: " + place.getService() + "." + operation.getName()); continue; }
            if ("boolean".equals(c.resultType)) {
                if (names.isEmpty() || names.contains("") || new HashSet<>(names).size() != names.size() || operation.getReturnAttribute().isEmpty())
                    errors.add("A Boolean service needs unique, nonempty token input and result names.");
            } else {
                if (!c.inputs.equals(names)) errors.add("Process inputs differ from catalogue: expected " + c.inputs);
                if (!c.output.equals(operation.getReturnAttribute())) errors.add("Process result differs from catalogue: expected " + c.output);
            }
        }
        return errors;
    }
    /** True when every operation of the place resolves to an instance of the linked deployment. */
    public boolean isDeployed(ProcessElement place) {
        if (deployment == null || place.getServiceOperations().isEmpty()) return false;
        for (ServiceOperation operation : place.getServiceOperations())
            if (endpoint(place.getService(), operation.getName(), identity(place)) == null) return false;
        return true;
    }
    /** Node of the linked deployment that runs this place, or "" when it is not deployed. */
    public String nodeFor(ProcessElement place) {
        if (!isDeployed(place)) return "";
        return endpoint(place.getService(), place.getServiceOperations().get(0).getName(), identity(place)).node;
    }
    /** Set an operation's contract from the catalogue. Boolean services keep their token aliases (default token -> token). */
    public void applyCatalogue(ProcessElement place, String operation) {
        Contract c = contract(place.getService(), operation);
        if (c == null) throw new IllegalArgumentException("Choose a catalogue operation.");
        ServiceOperation existing = null;
        for (ServiceOperation candidate : place.getServiceOperations()) if (operation.equals(candidate.getName())) existing = candidate;
        ServiceOperation op = new ServiceOperation(operation);
        if ("boolean".equals(c.resultType)) {
            if (existing != null && !existing.getArguments().isEmpty()) for (ServiceArgument argument : existing.getArguments()) op.addArgument(new ServiceArgument(argument));
            else op.addArgument(new ServiceArgument("token", "String", "String", false));
            op.setReturnAttribute(existing != null && !existing.getReturnAttribute().isEmpty() ? existing.getReturnAttribute() : "token");
        } else {
            for (String input : c.inputs) op.addArgument(new ServiceArgument(input, "String", "String", false));
            op.setReturnAttribute(c.output);
        }
        List<ServiceOperation> selected = new ArrayList<>(place.getServiceOperations());
        selected.removeIf(other -> operation.equals(other.getName()));
        selected.add(op);
        place.setServiceOperations(selected);
    }

    /** A physical node of the shared infrastructure. */
    public static final class Node {
        public String node = "", channel = "", address = "";
        public final List<Integer> ports = new ArrayList<>();
        public String describe() { return node + " \u2014 " + address + (ports.isEmpty() ? "" : ":" + ports.get(0)); }
    }
    /** Nodes of InfrastructureDefinitionFolder/SingleHost.json, the infrastructure the editors use by default. */
    public static List<Node> infrastructureNodes(File anchor) {
        List<Node> nodes = new ArrayList<>();
        File root = findCommon(anchor);
        File file = root == null ? null : new File(root, "InfrastructureDefinitionFolder/SingleHost.json");
        if (file == null || !file.isFile()) return nodes;
        try {
            for (Object item : array(read(file), "nodes")) {
                JSONObject data = (JSONObject)item; Node n = new Node();
                n.node = text(data, "node"); n.channel = text(data, "channel"); n.address = text(data, "address");
                for (Object port : array(data, "basePorts")) n.ports.add(((Number)port).intValue());
                nodes.add(n);
            }
        } catch (Exception ex) { nodes.clear(); }
        return nodes;
    }
    /** Deployment instance name derived from a place label: letters and digits only, unique among taken names. */
    public static String instanceNameFor(String label, Set<String> taken) {
        String base = label == null ? "" : label.replaceAll("[^A-Za-z0-9]", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) base = "Place" + base;
        String name = base;
        for (int i = 2; taken.contains(name); i++) name = base + i;
        return name;
    }
    public static String identity(ProcessElement place) { return place.getServiceInstance().isEmpty() ? place.getService() : place.getServiceInstance(); }
    public void apply(ProcessElement place, String operation, String instance) {
        Endpoint e = endpoint(place.getService(), operation, instance);
        if (e == null) throw new IllegalArgumentException("Choose a matching deployment instance.");
        ServiceOperation op = new ServiceOperation(operation); op.setReturnAttribute(e.output);
        for (ServiceArgument input : e.arguments) op.addArgument(new ServiceArgument(input));
        List<ServiceOperation> selected = new ArrayList<>(place.getServiceOperations());
        selected.removeIf(existing -> operation.equals(existing.getName()));
        selected.add(op);
        place.setServiceInstance(instance); place.setServiceOperations(selected);
    }
    /** Keep unresolved values visible without making them legitimate choices. */
    public static JComboBox<String> choices(List<String> valid, String current) {
        JComboBox<String> combo = new JComboBox<>(); combo.addItem("");
        for (String value : valid) combo.addItem(value);
        if (current != null && !current.isEmpty() && !valid.contains(current)) combo.addItem(current);
        combo.setSelectedItem(current == null ? "" : current); combo.setEditable(false);
        combo.setMinimumSize(new java.awt.Dimension(0, 25)); combo.setPreferredSize(new java.awt.Dimension(220, 25));
        combo.setRenderer(new DefaultListCellRenderer() {
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                String name = value == null ? "" : value.toString();
                if (!name.isEmpty() && !valid.contains(name)) { setText("Unresolved: " + name); if (!selected) setForeground(java.awt.Color.RED); }
                return this;
            }
        });
        return combo;
    }
}
