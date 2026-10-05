package org.btsn.deployment;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Exercises the actual upload and start protocol without putting services on the host classpath. */
public final class DeploymentIntegrationCheck {
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path bundle = Path.of(args[0]).toAbsolutePath(), receiver = Path.of(args[1]).toAbsolutePath();
        Path root = Files.createTempDirectory("btsn-remote-host-");
        int port; try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        Path log = root.resolve("receiver.log");
        Process host = new ProcessBuilder(java(), "-jar", receiver.toString(), "127.0.0.1", Integer.toString(port),
                root.toString(), "integration-token").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && (!Files.exists(log) || !Files.readString(log).contains("HOST READY"))) {
                require(host.isAlive(), "Receiver exited"); Thread.sleep(50);
            }
            String hash = DeploymentClient.digest(bundle);
            try {
                DeploymentClient.request("127.0.0.1", port, "wrong", "DEPLOY", hash, null, null, bundle);
                throw new AssertionError("Unauthenticated upload accepted");
            } catch (IOException expected) { require(expected.getMessage().contains("authentication"), expected.toString()); }
            System.out.println(DeploymentClient.request("127.0.0.1", port, "integration-token", "DEPLOY", hash, null, null, bundle));
            try {
                DeploymentClient.request("127.0.0.1", port, "integration-token", "DEPLOY", "0".repeat(64), null, null, bundle);
                throw new AssertionError("Invalid checksum accepted");
            } catch (IOException expected) { require(expected.getMessage().contains("checksum"), expected.toString()); }
            Path released = root.resolve("releases").resolve(hash);
            require(!Files.exists(released.resolve("btsn.common/src")), "Source leaked into destination");
            require(!Files.exists(released.resolve("btsn.petrinet.places.p1/bin")), "Eclipse bin leaked into destination");
            for (String component : List.of("p1", "p2", "p3", "p4", "p5", "p6", "monitor")) {
                System.out.println(DeploymentClient.request("127.0.0.1", port, "integration-token", "START", hash, component, "v001", null));
                require(host.isAlive(), "Ant start request stopped the host");
                String again = DeploymentClient.request("127.0.0.1", port, "integration-token", "START", hash, component, "v001", null);
                require(again.startsWith("ALREADY RUNNING"), "Duplicate service started");
                if (!component.equals("monitor")) checkInvocation(released, component, Path.of(args[2]).toAbsolutePath());
            }
            Path malicious = root.resolve("bad.zip");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(malicious))) {
                out.putNextEntry(new ZipEntry("../escaped")); out.write(1); out.closeEntry();
            }
            try {
                DeploymentClient.request("127.0.0.1", port, "integration-token", "DEPLOY", DeploymentClient.digest(malicious), null, null, malicious);
                throw new AssertionError("Path escape accepted");
            } catch (IOException expected) { require(expected.getMessage().contains("escapes"), expected.toString()); }
            System.out.println("PASS: upload, checksum, authenticated requests, all six service runtimes + Monitor start, duplicate start, received-JAR invocations, host stays running, archive path protection");
        } finally {
            host.destroy(); if (!host.waitFor(10, TimeUnit.SECONDS)) host.destroyForcibly();
            System.out.println("Receiver test files: " + root);
        }
    }
    private static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    private static void checkInvocation(Path released, String component, Path checks) throws Exception {
        List<String> cp = new ArrayList<>(); cp.add(checks.toString());
        Path jar = released.resolve("btsn.petrinet.places." + component + "/target/btsn.petrinet.places." + component + ".jar");
        Path infrastructure = released.resolve("btsn.common/target/host-runtime/btsn-infrastructure.jar");
        cp.add(jar.toString()); cp.add(infrastructure.toString());
        for (String dir : List.of("btsn.common/lib", "btsn.services/target/deployment/lib", "btsn.services/target/deployment/services")) {
            try (java.util.stream.Stream<Path> files = Files.list(released.resolve(dir))) {
                files.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(p -> cp.add(p.toString()));
            }
        }
        for (List<String> test : List.of(
                List.of("HostClasspathCheck", released.resolve("btsn.services/target/deployment").toString(), jar.toString(), infrastructure.toString()),
                List.of("org.btsn.invocation.BusinessCapabilityResolverTest", released.resolve("btsn.common").toString(), "org.btsn.places.P" + component.substring(1) + "_Place"))) {
            List<String> command = new ArrayList<>(List.of(java(), "-cp", String.join(File.pathSeparator, cp))); command.addAll(test);
            Process check = new ProcessBuilder(command).directory(released.resolve("btsn.petrinet.places." + component).toFile()).inheritIO().start();
            require(check.waitFor() == 0, "Received-JAR check failed: " + component);
        }
    }
}
