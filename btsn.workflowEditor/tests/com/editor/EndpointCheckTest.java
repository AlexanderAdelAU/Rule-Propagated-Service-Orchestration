package com.editor;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;

/** Before a run, each REST service of the process is asked for an answer; one that gives none is named with the reason. */
public final class EndpointCheckTest {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile();
        // A service that only takes POST, as the Vercel function does: its 405 still counts as answering.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(405, -1); exchange.close(); });
        server.start();
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) { closed = socket.getLocalPort(); }
        String up = "http://127.0.0.1:" + server.getAddress().getPort() + "/credit/assess", down = "http://127.0.0.1:" + closed + "/credit/assess";
        try {
            check(EndpointCheck.probe(up, 2000) == null, "A service answering 405 counted as not answering");
            check("connection refused".equals(EndpointCheck.probe(down, 2000)), "Closed port: " + EndpointCheck.probe(down, 2000));
            check(EndpointCheck.isLoopback(down) && EndpointCheck.isLoopback("http://localhost/x") && !EndpointCheck.isLoopback("https://example.vercel.app/api/credit/assess"),
                "Loopback addresses not recognised");

            // The supplied REST credit check, its deployment pointed at each address in turn.
            Path common = Files.createTempDirectory("endpoint-check-").resolve("btsn.common");
            Path real = root.toPath().resolve("btsn.common");
            for (String file : Arrays.asList("BusinessServiceDefinitions/credit/Credit.json", ServiceRegistry.STANDARD_CATALOGUE,
                    "ProcessDefinitionFolder/credit/Workflow/Credit_Check_Rest_Workflow.json", "ServiceDeploymentFolder/credit/Credit_Check_Rest_Workflow.json")) {
                Path copy = common.resolve(file);
                Files.createDirectories(copy.getParent());
                Files.copy(real.resolve(file), copy);
            }
            Path deployment = common.resolve("ServiceDeploymentFolder/credit/Credit_Check_Rest_Workflow.json");
            String supplied = Files.readString(deployment);
            String endpoint = supplied.replaceAll("(?s).*\"endpoint\": \"([^\"]*)\".*", "$1");
            Path process = common.resolve("ProcessDefinitionFolder/credit/Workflow/Credit_Check_Rest_Workflow.json");
            for (String address : Arrays.asList(up, down)) {
                Files.writeString(deployment, supplied.replace(endpoint, address));
                Canvas canvas = new Canvas(); canvas.setDefinitionLocation(process.toFile()); canvas.loadFromJSON(Files.readString(process));
                List<String> silent = EndpointCheck.notAnswering(canvas, true, 2000);
                if (address.equals(up)) check(silent.isEmpty(), "Answering service reported: " + silent);
                else check(silent.equals(Arrays.asList("Place 'Credit': CreditLimitService at " + down
                    + " is not answering (connection refused). Start the service, or change its endpoint in Deploy.")), "Silent service not reported: " + silent);
                // From another machine, 127.0.0.1 names the host's own computer: not checked from here.
                check(EndpointCheck.notAnswering(canvas, false, 2000).isEmpty(), "Loopback endpoint checked for a multi-machine run");
            }
        } finally {
            server.stop(0);
        }
        System.out.println("PASS: REST services checked before a run: any HTTP answer counts, a closed port is named with its reason, loopback addresses only when the hosts run here");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
