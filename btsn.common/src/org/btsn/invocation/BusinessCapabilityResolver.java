package org.btsn.invocation;

import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Resolves rule-selected runtime operations to configured business implementations.
 * No business names, physical node names, class-name conventions or routing logic
 * are embedded here. Runtime identities come from generated activeService facts;
 * logical identities, contracts and implementation classes come from metadata.
 * Each instance is an immutable deployment snapshot; restart after metadata changes.
 */
public final class BusinessCapabilityResolver {
    private final Path runtimeDirectory;
    private final Map<String, Capability> runtimeBindings = new HashMap<>();
    private final Map<String, Capability> logicalBindings = new HashMap<>();
    private final Set<String> directOperations = new HashSet<>();

    public static BusinessCapabilityResolver forCurrentDeployment() throws Exception {
        // Same sibling common-project layout used by the existing rule loader.
        Path common = Paths.get(System.getProperty("btsn.common.dir", "../btsn.common"));
        return new BusinessCapabilityResolver(common);
    }

    public BusinessCapabilityResolver(Path common) throws Exception {
        this(common, Paths.get(""));
    }

    public BusinessCapabilityResolver(Path common, Path runtimeDirectory) throws Exception {
        this.runtimeDirectory = runtimeDirectory;
        org.btsn.deployment.DeploymentConfiguration deployment = new org.btsn.deployment.DeploymentConfiguration(common);
        JSONObject config = deployment.profile;
        JSONObject catalog = json(common.resolve(text(config, "catalog")));
        JSONObject infrastructure = deployment.infrastructure;
        require("Infrastructure".equals(text(infrastructure, "definitionType")),
                "Selected definition is not Infrastructure");
        List<List<String>> deploymentFacts = facts(common.resolve(text(config, "deploymentRules")), "activeService");
        List<List<String>> channels = facts(common.resolve(text(config, "deploymentRules")), "boundChannel");
        Map<String, JSONObject> nodes = new HashMap<>();
        for (Object item : array(infrastructure, "nodes")) {
            JSONObject node = object(item);
            String name = text(node, "node");
            require(nodes.put(name, node) == null, "Duplicate infrastructure node: " + name);
        }
        for (Object item : array(catalog, "services")) {
            JSONObject definition = object(item);
            Capability capability = new Capability(definition);
            String key = key(capability.logicalService, capability.operation);
            require(logicalBindings.put(key, capability) == null, "Duplicate catalogue capability: " + key);
        }
        Set<String> deployed = new HashSet<>();
        for (Object item : array(deployment.serviceDeployment, "capabilities")) {
            JSONObject placement = object(item);
            String logicalKey = key(text(placement, "service"), text(placement, "operation"));
            Capability capability = logicalBindings.get(logicalKey);
            require(capability != null, "Deployment has no catalogue entry: " + logicalKey);
            require(capability.active, "Deployment selects an inactive/unbound capability: " + logicalKey);
            require(deployed.add(logicalKey), "Duplicate deployment capability: " + logicalKey);
            require(capability.returnAttribute.equals(text(placement, "returnAttribute")),
                    "Conflicting return attribute: " + logicalKey);
            List<String> arguments = new ArrayList<>();
            for (Object argument : array(placement, "arguments")) {
                JSONObject definition = object(argument);
                require("String".equals(text(definition, "type")), "Unsupported argument type: " + logicalKey);
                arguments.add(text(definition, "name"));
            }
            require(capability.inputs.equals(arguments), "Conflicting input contract: " + logicalKey);
            JSONObject node = nodes.get(text(placement, "node"));
            require(node != null, "Capability refers to an undefined node: " + logicalKey);
            String channel = text(node, "channel");
            String address = text(node, "address");
            int matchedChannels = 0;
            for (List<String> fact : channels) {
                require(fact.size() == 2, "Malformed boundChannel deployment fact");
                if (fact.get(0).equals(channel)) {
                    require(fact.get(1).equals(address), "Deployment address conflicts: " + logicalKey);
                    matchedChannels++;
                }
            }
            require(matchedChannels == 1, "Missing/duplicate deployment channel: " + channel);
            String port = Integer.toString(deployment.port(placement));
            String runtime = null;
            for (List<String> fact : deploymentFacts) {
                require(fact.size() == 4, "Malformed activeService deployment fact");
                if (fact.get(1).equals(capability.operation) && fact.get(2).equals(channel) && fact.get(3).equals(port)) {
                    require(runtime == null, "Ambiguous runtime endpoint: " + logicalKey);
                    runtime = fact.get(0);
                }
            }
            require(runtime != null, "No generated runtime deployment fact: " + logicalKey);
            String runtimeKey = key(runtime, capability.operation);
            require(runtimeBindings.put(runtimeKey, capability) == null,
                    "Multiple capabilities assigned to runtime operation: " + runtimeKey);
        }
        for (Map.Entry<String, Capability> entry : logicalBindings.entrySet()) {
            require(!entry.getValue().active || deployed.contains(entry.getKey()),
                    "Active catalogue capability is not deployed: " + entry.getKey());
        }
        // Explicitly registered infrastructure operations keep their current invocation.
        // An unresolved business operation never falls through to a physical adapter.
        for (Object file : array(config, "directServiceRules")) {
            for (List<String> fact : facts(common.resolve(file.toString()), "activeService")) {
                require(fact.size() == 4, "Malformed direct activeService fact");
                String directKey = key(fact.get(0), fact.get(1));
                require(!runtimeBindings.containsKey(directKey) && !logicalBindings.containsKey(directKey),
                        "Business operation registered as infrastructure: " + directKey);
                require(directOperations.add(directKey), "Duplicate direct operation: " + directKey);
            }
        }
    }

    /** Resolve a physical or logical identity; the orchestration-selected operation stays unchanged. */
    public String resolve(String service, String operation, String returnAttribute, int argumentCount,
                          String ruleBaseVersion) throws Exception {
        String identity = service.substring(service.lastIndexOf('.') + 1);
        String invocationKey = key(identity, operation);
        Capability capability = runtimeBindings.get(invocationKey);
        if (capability == null) capability = logicalBindings.get(invocationKey);
        if (capability == null) {
            require(directOperations.contains(invocationKey), "No configured capability: " + invocationKey);
            return service;
        }
        require(capability.active, "Capability is inactive/unbound: " + invocationKey);
        require(capability.returnAttribute.equals(returnAttribute), "Invocation return contract mismatch: " + invocationKey);
        require(capability.inputs.size() == argumentCount, "Invocation argument count mismatch: " + invocationKey);
        if (ruleBaseVersion != null) {
            require(ruleBaseVersion.matches("v[0-9]+"), "Invalid rulebase version: " + ruleBaseVersion);
            require(operation.matches("[A-Za-z_$][A-Za-z0-9_$]*"), "Invalid operation: " + operation);
            Path installed = runtimeDirectory.resolve(Paths.get("RuleFolder." + ruleBaseVersion, operation, "Service.ruleml"));
            List<List<String>> identities = facts(installed, "localDefined");
            require(identities.contains(java.util.Collections.singletonList(capability.logicalService)),
                    "Installed rulebase does not select logical service " + capability.logicalService + ": " + installed);
            int selections = 0;
            for (List<String> identityFact : identities) {
                if (identityFact.size() == 1 && logicalBindings.containsKey(key(identityFact.get(0), operation))) selections++;
            }
            require(selections == 1, "Installed rulebase has ambiguous logical service identities: " + installed);
            List<List<String>> bindings = facts(installed, "canonicalBinding");
            List<String> installedInputs = new ArrayList<>();
            for (List<String> binding : bindings) {
                if (binding.size() == 3 && binding.get(0).equals(operation)) {
                    require(binding.get(1).equals(returnAttribute), "Installed return contract conflicts: " + installed);
                    installedInputs.add(binding.get(2));
                }
            }
            require(installedInputs.equals(capability.inputs), "Installed input contract conflicts: " + installed);
        }
        return capability.implementationClass;
    }

    /** Observation metadata only; uses the same installed contract as invocation. */
    public String logicalService(String service, String operation, String ruleBaseVersion) throws Exception {
        String identity = service.substring(service.lastIndexOf('.') + 1);
        Capability capability = runtimeBindings.get(key(identity, operation));
        if (capability == null) capability = logicalBindings.get(key(identity, operation));
        if (capability == null) return null; // Infrastructure has no business label.
        resolve(service, operation, capability.returnAttribute, capability.inputs.size(), ruleBaseVersion);
        return capability.logicalService;
    }

    private static final class Capability {
        final String logicalService;
        final String operation;
        final String implementationClass;
        final String returnAttribute;
        final List<String> inputs = new ArrayList<>();
        final boolean active;

        Capability(JSONObject definition) {
            logicalService = text(definition, "service");
            operation = text(definition, "operation");
            implementationClass = text(definition, "implementationClass");
            returnAttribute = text(definition, "returnAttribute");
            active = "active".equals(text(definition, "status"));
            for (Object input : array(definition, "inputs")) {
                require(input instanceof String && !input.toString().trim().isEmpty(), "Invalid catalogue input");
                inputs.add(input.toString());
            }
        }
    }

    private static JSONObject json(Path file) throws Exception {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return object(new JSONParser().parse(reader));
        }
    }

    private static JSONObject object(Object value) {
        require(value instanceof JSONObject, "Expected a JSON object");
        return (JSONObject) value;
    }

    private static JSONArray array(JSONObject object, String field) {
        require(object.get(field) instanceof JSONArray, "Missing/invalid array: " + field);
        return (JSONArray) object.get(field);
    }

    private static String text(JSONObject object, String field) {
        Object value = object.get(field);
        require(value != null && !value.toString().trim().isEmpty(), "Missing/invalid field: " + field);
        return value.toString().trim();
    }

    private static String key(String service, String operation) {
        return service + "." + operation;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Capability resolution failed: " + message);
    }

    /** Read ground Atom facts only. Rules containing Var arguments are not deployment facts. */
    private static List<List<String>> facts(Path file, String relation) throws Exception {
        String xml = Files.readString(file, StandardCharsets.UTF_8).replaceFirst("^\\s*<\\?xml[^?]*\\?>", "");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        NodeList atoms = factory.newDocumentBuilder().parse(new InputSource(new StringReader("<facts>" + xml + "</facts>")))
                .getElementsByTagName("Atom");
        List<List<String>> result = new ArrayList<>();
        for (int i = 0; i < atoms.getLength(); i++) {
            Element atom = (Element) atoms.item(i);
            boolean inRule = false;
            for (Node ancestor = atom.getParentNode(); ancestor != null; ancestor = ancestor.getParentNode()) {
                if ("Implies".equals(ancestor.getNodeName()) || "Query".equals(ancestor.getNodeName())) inRule = true;
            }
            if (inRule) continue;
            List<String> values = new ArrayList<>();
            String name = null;
            boolean ground = true;
            for (Node child = atom.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                if ("Rel".equals(child.getNodeName())) name = child.getTextContent().trim();
                else if ("Ind".equals(child.getNodeName())) values.add(child.getTextContent().trim());
                else ground = false;
            }
            if (ground && relation.equals(name)) result.add(values);
        }
        return result;
    }
}
