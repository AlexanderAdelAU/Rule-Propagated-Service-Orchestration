package org.btsn.invocation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Serves Java place services over HTTP, so another organisation can run a service in its own system and an
 * orchestration host calls it with the rest adapter (RestAdapter). The same class runs in-process or here: only the
 * service deployment's placement changes.
 *
 * Each POST carries the call RestAdapter sends:
 *
 *   {"service": ..., "operation": ..., "sequenceId": ..., "version": ..., "inputs": {name: value, ...}}
 *
 * The service and operation are looked up in the catalogue, which gives the implementation class and the order of
 * the inputs. The class is made as the host makes it (with the sequence id, or with no arguments) and the operation
 * called with each input as text. The answer, with HTTP 200, is what the operation returned:
 * {returnAttribute: {..., "routing_decision": {"routing_path": ...}}}.
 *
 * A GET answers what is served (the editor's check before a run asks each endpoint for an answer); GET /stop stops
 * the server, asked from this machine only.
 *
 * args: port=8090 catalogue=<catalogue JSON> [services=A,B: serve only these] [bind=127.0.0.1] [log=<file>]
 */
public final class RestServiceServer {
    private final Map<String, JSONObject> operations = new LinkedHashMap<>();   // "service.operation" -> catalogue entry
    private final PrintStream log;
    private HttpServer server;

    RestServiceServer(Path catalogue, Set<String> services, PrintStream log) throws Exception {
        this.log = log;
        JSONObject json = (JSONObject) new JSONParser().parse(Files.readString(catalogue));
        for (Object item : (JSONArray) json.get("services")) {
            JSONObject entry = (JSONObject) item;
            String service = String.valueOf(entry.get("service"));
            if (entry.get("implementationClass") == null || (!services.isEmpty() && !services.contains(service))) continue;
            operations.put(service + "." + entry.get("operation"), entry);
        }
        if (operations.isEmpty()) throw new IllegalArgumentException("No Java service to serve from " + catalogue + (services.isEmpty() ? "" : " among " + services));
    }

    /** What this server answers, one "service.operation" each. */
    List<String> served() { return new ArrayList<>(operations.keySet()); }

    void start(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        log.println("Serving " + served() + " over REST at http://" + bind + ":" + port + "/");
    }

    void stop() {
        if (server != null) server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdown();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                if (exchange.getRequestURI().getPath().endsWith("/stop") && exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                    answer(exchange, 200, "text/plain", "Stopping\n");
                    new Thread(() -> { log.println("Stopped"); stop(); }).start();
                    return;
                }
                answer(exchange, 200, "text/plain", "POST a call to this address. Serving: " + String.join(", ", served()) + "\n");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) { answer(exchange, 405, "text/plain", "POST a call\n"); return; }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            answer(exchange, 200, "application/json", call(body));
        } catch (IllegalArgumentException e) {
            answer(exchange, 400, "text/plain", e.getMessage() + "\n");
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            log.println("Call failed: " + cause);
            answer(exchange, 500, "text/plain", "The service failed: " + cause + "\n");
        }
    }

    /** One call: the operation's result, as the operation returned it. */
    String call(String body) throws Exception {
        JSONObject request;
        try { request = (JSONObject) new JSONParser().parse(body); }
        catch (Exception e) { throw new IllegalArgumentException("The body is not a JSON call: " + e.getMessage()); }
        String service = String.valueOf(request.get("service")), operation = String.valueOf(request.get("operation"));
        JSONObject entry = operations.get(service + "." + operation);
        if (entry == null) throw new IllegalArgumentException(service + "." + operation + " is not served here; serving " + served());
        JSONObject inputs = request.get("inputs") instanceof JSONObject ? (JSONObject) request.get("inputs") : new JSONObject();
        List<String> args = new ArrayList<>();
        for (Object name : (JSONArray) entry.get("inputs")) {
            Object value = inputs.get(name);
            args.add(value == null ? null : value instanceof JSONObject || value instanceof JSONArray ? value.toString() : String.valueOf(value));
        }
        Class<?> type = Class.forName(String.valueOf(entry.get("implementationClass")));
        Class<?>[] parameters = new Class<?>[args.size()];
        Arrays.fill(parameters, String.class);
        Method method = type.getMethod(operation, parameters);
        Object result = method.invoke(instance(type, String.valueOf(request.getOrDefault("sequenceId", ""))), args.toArray());
        log.println(service + "." + operation + " for token " + request.get("sequenceId") + " (" + request.get("version") + "): " + result);
        return String.valueOf(result);
    }

    /** As the host makes a service: with the sequence id if the class takes one, else with no arguments. */
    private static Object instance(Class<?> type, String sequenceId) throws Exception {
        try {
            Constructor<?> withId = type.getConstructor(String.class);
            return withId.newInstance(sequenceId);
        } catch (NoSuchMethodException none) {
            return type.getConstructor().newInstance();
        }
    }

    private static void answer(HttpExchange exchange, int status, String type, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) { int eq = arg.indexOf('='); if (eq > 0) options.put(arg.substring(0, eq), arg.substring(eq + 1)); }
        if (!options.containsKey("catalogue")) {
            System.err.println("usage: RestServiceServer port=8090 catalogue=<catalogue JSON> [services=A,B] [bind=127.0.0.1] [log=<file>]");
            System.exit(2);
        }
        Set<String> services = new HashSet<>();
        for (String s : options.getOrDefault("services", "").split(",")) if (!s.isBlank()) services.add(s.trim());
        PrintStream log = options.containsKey("log")
            ? new PrintStream(Files.newOutputStream(Path.of(options.get("log"))), true, StandardCharsets.UTF_8) : System.out;
        new RestServiceServer(Path.of(options.get("catalogue")), services, log)
            .start(options.getOrDefault("bind", "127.0.0.1"), Integer.parseInt(options.getOrDefault("port", "8090")));
    }
}
