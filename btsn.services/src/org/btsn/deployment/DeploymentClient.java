package org.btsn.deployment;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;

/** Ant's client. Transfers bytes to a host, then explicitly requests service startup. */
public final class DeploymentClient {
    static final String PROTOCOL = "BTSN-DEPLOY-1";
    static String digest(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) md.update(b, 0, n);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : md.digest()) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    static String request(String host, int port, String token, String command,
                          String release, String component, String version, Path zip) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 10000);
            socket.setSoTimeout(120000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out.writeUTF(PROTOCOL); out.writeUTF(token); out.writeUTF(command); out.writeUTF(release);
            out.flush();
            if (!in.readBoolean()) throw new IOException(in.readUTF());
            if ("DEPLOY".equals(command)) {
                out.writeLong(Files.size(zip));
                Files.copy(zip, out);
            } else {
                out.writeUTF(component); out.writeUTF(version);
            }
            out.flush();
            boolean ok = in.readBoolean(); String response = in.readUTF();
            if (!ok) throw new IOException(response);
            return response;
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("Usage: DeploymentClient host port token bundle.zip component version");
        Path zip = Paths.get(args[3]); String release = digest(zip);
        System.out.println(request(args[0], Integer.parseInt(args[1]), args[2], "DEPLOY", release, null, null, zip));
        System.out.println(request(args[0], Integer.parseInt(args[1]), args[2], "START", release, args[4], args[5], null));
    }
}
