package org.btsn.deployment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** Combines reusable network resources with a separately selected service placement. */
public final class DeploymentConfiguration {
    public final JSONObject profile;
    public final JSONObject infrastructure;
    public final JSONObject serviceDeployment;
    public final Path infrastructureFile;
    private final Map<String, JSONObject> operations = new HashMap<>();
    private final Map<String, JSONObject> placements = new HashMap<>();
    private final Map<String, JSONObject> nodes = new HashMap<>();

    public DeploymentConfiguration(Path common) throws Exception {
        profile = read(common.resolve("BusinessServiceDefinitions/Deployment.json"));
        infrastructureFile = common.resolve(text(profile, "infrastructure"));
        infrastructure = read(infrastructureFile);
        serviceDeployment = read(common.resolve(text(profile, "serviceDeployment")));
        require("Infrastructure".equals(infrastructure.get("definitionType")), "Selected definition is not Infrastructure");
        require(!infrastructure.containsKey("capabilities"), "Physical infrastructure must not contain service capabilities");
        require("ServiceDeployment".equals(serviceDeployment.get("definitionType")), "Selected definition is not ServiceDeployment");
        require(!serviceDeployment.containsKey("nodes"), "Service deployment must not contain physical nodes");
        if (serviceDeployment.containsKey("catalog"))
            require(text(profile, "catalog").equals(text(serviceDeployment, "catalog")), "Deployment catalogue conflicts with Deployment.json");
        for (Object item : array(read(common.resolve(text(profile, "catalog"))), "services")) {
            JSONObject service = (JSONObject)item;
            if (!"active".equals(service.get("status"))) continue;
            String key = text(service, "service") + "\u0000" + text(service, "operation");
            require(operations.put(key, service) == null, "Duplicate catalogue capability: " + key.replace('\u0000', '.'));
        }
        Set<String> sockets = new HashSet<>();
        Map<String,String> channels = new HashMap<>();
        for (Object item : array(infrastructure, "nodes")) {
            JSONObject node = (JSONObject)item;
            String name = text(node, "node"), channel = text(node, "channel"), address = text(node, "address");
            require(nodes.put(name, node) == null, "Duplicate infrastructure node: " + name);
            String previous = channels.put(channel, address);
            require(previous == null || previous.equals(address), "Conflicting address for channel: " + channel);
            JSONArray ports = array(node, "basePorts");
            require(!ports.isEmpty(), "No fixed ports for node: " + name);
            for (Object value : ports) {
                int port = integer(value, "base port");
                require(port > 0 && port <= 45535, "Invalid base port for node: " + name);
                require(sockets.add(address + ":" + channel + ":" + port), "Duplicate physical endpoint: " + name + ":" + port);
            }
        }
        require(!nodes.isEmpty(), "No infrastructure nodes");
        for (Object item : array(serviceDeployment, "capabilities")) {
            JSONObject placement = (JSONObject)item;
            require(!placement.containsKey("basePort") && !placement.containsKey("address") && !placement.containsKey("channel"),
                    "Service placement must select a fixed port slot, not network settings");
            port(placement);
            String service = text(placement, "service"), operation = text(placement, "operation");
            JSONObject definition = operations.get(service + "\u0000" + operation);
            require(definition != null, "Undefined catalogue operation: " + service + "." + operation);
            String instance = placement.containsKey("instance") ? text(placement, "instance") : service;
            require(placements.put(instance + "\u0000" + operation, placement) == null, "Duplicate deployment capability: " + instance + "." + operation);
            String adapter = placement.containsKey("invocationAdapter") ? text(placement, "invocationAdapter") : "";
            java.util.List<String> inputs = inputNames(placement);
            if (adapter.isEmpty()) {
                require(!"boolean".equals(definition.get("resultType")), "Boolean service requires an explicit boolean-token adapter: " + service);
                require(array(definition, "inputs").equals(inputs), "Placement inputs differ from catalogue: " + service + "." + operation);
                require(text(definition, "returnAttribute").equals(text(placement, "returnAttribute")), "Conflicting return attribute between placement and catalogue: " + service + "." + operation);
            } else {
                require("boolean-token".equals(adapter) && "boolean".equals(definition.get("resultType")) &&
                    array(definition, "inputs").equals(java.util.Arrays.asList("data")) && "decision".equals(definition.get("returnAttribute")),
                    "Invalid Boolean adapter contract: " + service + "." + operation);
                text(placement, "returnAttribute");
            }
            require(new HashSet<>(inputs).size() == inputs.size(), "Duplicate placement inputs: " + instance);
        }
    }

    private static java.util.List<String> inputNames(JSONObject operation) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Object item : array(operation, "arguments")) names.add(text((JSONObject)item, "name"));
        return names;
    }

    /** Checks a process before rule generation; routing topology is validated separately. */
    public void validateWorkflow(JSONObject workflow) {
        // The editor association is a selection hint. Reusable processes may run under another
        // profile, provided every instance operation has the same declared contract.
        for (Object item : array(workflow, "elements")) {
            JSONObject place = (JSONObject)item;
            if (!"PLACE".equals(place.get("type"))) continue;
            String service = text(place, "service");
            String instance = place.containsKey("serviceInstance") ? text(place, "serviceInstance") : service;
            JSONArray declared = array(place, "operations");
            require(!declared.isEmpty(), "Place has no operation: " + place.get("id"));
            Set<String> used = new HashSet<>();
            for (Object op : declared) {
                require(op instanceof JSONObject, "Process operation must declare its input and result contract: " + place.get("id"));
                JSONObject operation = (JSONObject)op;
                String name = text(operation, "name");
                require(used.add(name), "Duplicate process operation: " + name);
                JSONObject placement = placements.get(instance + "\u0000" + name);
                require(placement != null && service.equals(placement.get("service")), "Unresolved process instance: " + service + "." + name + " / " + instance);
                require(inputNames(placement).equals(inputNames(operation)), "Process inputs differ from deployment: " + instance + "." + name);
                String output = operation.containsKey("returnAttribute") ? text(operation, "returnAttribute") : text(place, "returnAttribute");
                require(output.equals(text(placement, "returnAttribute")), "Process result differs from deployment: " + instance + "." + name);
            }
        }
    }

    public JSONObject node(String name) {
        JSONObject node = nodes.get(name);
        require(node != null, "Capability refers to an undefined node: " + name);
        return node;
    }

    public int port(JSONObject placement) {
        JSONArray ports = array(node(text(placement, "node")), "basePorts");
        int slot = integer(placement.get("portSlot"), "port slot");
        require(slot >= 0 && slot < ports.size(), "Undefined port slot for node: " + text(placement, "node"));
        return integer(ports.get(slot), "base port");
    }

    public static JSONObject read(Path file) throws Exception {
        return (JSONObject)new JSONParser().parse(Files.readString(file));
    }
    public static String text(JSONObject object, String key) {
        Object value = object.get(key);
        require(value != null && !value.toString().trim().isEmpty(), "Missing " + key);
        return value.toString();
    }
    public static JSONArray array(JSONObject object, String key) {
        require(object.get(key) instanceof JSONArray, "Missing array: " + key);
        return (JSONArray)object.get(key);
    }
    private static int integer(Object value, String label) {
        require(value instanceof Number, "Invalid " + label);
        long number = ((Number)value).longValue();
        require(number == ((Number)value).doubleValue() && number >= 0 && number <= Integer.MAX_VALUE, "Invalid " + label);
        return (int)number;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
