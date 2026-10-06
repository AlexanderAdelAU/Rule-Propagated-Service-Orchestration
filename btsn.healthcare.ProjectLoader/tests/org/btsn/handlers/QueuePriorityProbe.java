package org.btsn.handlers;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.btsn.utils.XPathHelperCommon;

/** Test-only worker; queue admission and selection are the unchanged P1 implementation. */
public final class QueuePriorityProbe {
    private static final int BLOCKER = 3000000;
    private static final List<Integer> ARRIVALS = List.of(3040000, 3030000, 3020000, 3010000,
            2020000, 2010000, 1020000, 1010000);
    private static final List<Integer> EXPECTED = List.of(BLOCKER, 1010000, 1020000,
            2010000, 2020000, 3010000, 3020000, 3030000, 3040000);
    private static final class Visit {
        final int id;
        long queued, dequeued, started, ended;
        Visit(int id) { this.id = id; }
    }
    private static final class QueuePoint {
        final long time;
        final int depth;
        QueuePoint(long time, int depth) { this.time = time; this.depth = depth; }
    }
    /** Synchronization uses the reactor's own lock; timestamps bracket actual admission/dequeue. */
    private static final class ObservedReactor extends EventReactor {
        final Map<Integer, Visit> visits = new LinkedHashMap<>();
        final List<QueuePoint> depth = new ArrayList<>();
        final long origin = System.nanoTime();
        ObservedReactor(int port) throws Exception { super("1", Integer.toString(port)); }
        long now() { return System.nanoTime() - origin; }
        @Override public synchronized void putScheduledToken(String packet) throws InterruptedException, IOException {
            int id = id(packet);
            long before = now();
            int previous = getQueueSize();
            super.putScheduledToken(packet);
            if (getQueueSize() != previous + 1) throw new IOException("Admission failed or duplicate token: " + id);
            Visit visit = new Visit(id);
            visit.queued = before;
            visits.put(id, visit);
            depth.add(new QueuePoint(now(), getQueueSize()));
            notifyAll();
        }
        @Override public synchronized TreeMap<Long, String> getScheduledToken() throws InterruptedException {
            TreeMap<Long, String> packet = new TreeMap<>(super.getScheduledToken());
            Visit visit = visits.get(id(packet.firstEntry().getValue()));
            visit.dequeued = now();
            depth.add(new QueuePoint(visit.dequeued, getQueueSize()));
            return packet;
        }
        synchronized void awaitBacklog(int count) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (getQueueSize() != count) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Backlog not confirmed: " + getQueueSize() + "/" + count);
                wait(20);
            }
            if (!visits.keySet().containsAll(ARRIVALS.subList(0, count)))
                throw new AssertionError("Wrong tokens in observed backlog");
        }
    }
    private static int id(String packet) {
        try { return Integer.parseInt(new XPathHelperCommon().findMultipleXMLItems(packet, "//header/*").get("sequenceId")); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid probe packet", e); }
    }
    private static String packet(int id) {
        return "<servicePacket><header><sequenceId>" + id + "</sequenceId><ruleBaseVersion>v00" + id / 1000000
                + "</ruleBaseVersion><priortiseSID>true</priortiseSID></header>"
                + "<joinAttribute><notAfter>" + (System.currentTimeMillis() + 60000) + "</notAfter></joinAttribute>"
                + "<service><serviceName>QueuePriorityProbe</serviceName><operationName>controlledWork</operationName></service>"
                + "<monitorData><eventArrivalTime>0</eventArrivalTime><lostEvents>0</lostEvents>"
                + "<completedJoin>false</completedJoin></monitorData></servicePacket>";
    }
    private static void send(DatagramSocket socket, int port, int id) throws Exception {
        byte[] bytes = packet(id).getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
    }
    public static void main(String[] args) throws Exception {
        String reactorOrigin = EventReactor.class.getProtectionDomain().getCodeSource().getLocation().toString();
        if (!reactorOrigin.endsWith("/btsn.petrinet.places.p1.jar"))
            throw new AssertionError("Expected packaged P1 scheduler, loaded " + reactorOrigin);
        Path output = Path.of(args[0]);
        int repeats = Integer.parseInt(args[1]), requestedPort = Integer.parseInt(args[2]);
        boolean openResults = args.length < 4 || Boolean.parseBoolean(args[3]);
        if (repeats < 1 || repeats > 20) throw new IllegalArgumentException("repeats must be 1..20");
        Files.createDirectories(output);
        StringBuilder summary = new StringBuilder("CONTROLLED QUEUE-PRIORITY RESULTS\n"
                + "Real P1 UDP queue; controlled worker; healthcare/Monitor databases are not used.\n");
        for (int run = 1; run <= repeats; run++) {
            int port = requestedPort;
            if (port == 0) try (DatagramSocket unused = new DatagramSocket(0, InetAddress.getLoopbackAddress())) { port = unused.getLocalPort(); }
            summary.append("Run ").append(run).append(": ").append(experiment(output.resolve("run-" + run), port)).append('\n');
        }
        summary.append("PASS: ").append(repeats).append(" observed-backlog runs; v001 overtakes v002/v003; occupied work finishes first.\n");
        Files.writeString(output.resolve("summary.txt"), summary);
        System.out.print(summary);
        Path report = output.resolve("run-1/timeline.html").toAbsolutePath().normalize();
        Files.writeString(output.resolve("viewer-status.txt"), openResults ? openTimeline(report)
                : "Automatic graph opening disabled. Open this run's graph: " + report + "\n");
    }
    private static String openTimeline(Path report) {
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported())
            return "No desktop browser available. Open this run's graph: " + report + "\n";
        try {
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE))
                return "Browser opening unavailable. Open this run's graph: " + report + "\n";
            desktop.browse(report.toUri());
            return "Opened this run's queue-priority graph in the default browser: " + report + "\n";
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return "The test passed, but the graph could not open automatically (" + e.getMessage()
                    + "). Open: " + report + "\n";
        }
    }
    private static String experiment(Path output, int port) throws Exception {
        ObservedReactor reactor = new ObservedReactor(port);
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                for (int i = 0; i < EXPECTED.size(); i++) {
                    int token = id(reactor.getScheduledToken().firstEntry().getValue());
                    Visit visit = reactor.visits.get(token);
                    visit.started = reactor.now();
                    order.add(token);
                    if (token == BLOCKER) {
                        busy.countDown();
                        if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Blocker gate timed out");
                    } else Thread.sleep(80); // Controlled work duration, never used to infer backlog readiness.
                    visit.ended = reactor.now();
                }
            } catch (Throwable e) { failure.set(e); }
            finally { done.countDown(); }
        }, "priority-probe-single-worker");
        worker.start();
        long released;
        try (DatagramSocket sender = new DatagramSocket()) {
            send(sender, port, BLOCKER);
            if (!busy.await(10, TimeUnit.SECONDS)) throw new AssertionError("Worker did not enter blocker");
            for (int i = 0; i < ARRIVALS.size(); i++) {
                send(sender, port, ARRIVALS.get(i));
                reactor.awaitBacklog(i + 1); // Each arrival acknowledged by the real queue before the next send.
            }
            synchronized (reactor) {
                if (reactor.getQueueSize() != 8 || order.size() != 1) throw new AssertionError("Contended queue was not held");
                released = reactor.now();
                release.countDown();
            }
            if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("Queue did not drain");
            if (failure.get() != null) throw new AssertionError("Worker failed", failure.get());
            if (!order.equals(EXPECTED)) throw new AssertionError("Priority order violated: " + order);
            if (reactor.getQueueSize() != 0 || reactor.getLostEvents() != 0) throw new AssertionError("Lost or undrained tokens");
            long previousEnd = 0;
            for (int token : EXPECTED) {
                Visit v = reactor.visits.get(token);
                if (!(v.queued <= v.dequeued && v.dequeued <= v.started && v.started <= v.ended && previousEnd <= v.started))
                    throw new AssertionError("Invalid/non-serial intervals: " + token);
                if (token != BLOCKER && !(v.queued < released && v.dequeued >= released))
                    throw new AssertionError("Token was not waiting at release: " + token);
                previousEnd = v.ended;
            }
            write(output, reactor, released, port, order);
            return "PASS; 8 tokens confirmed waiting before release; queue drained; zero lost tokens.\n"
                    + "  Actual execution: " + order + "\n"
                    + "  v003 blocker finishes first, then v001, v001, v002, v002, v003, v003, v003, v003.";
        } finally {
            release.countDown();
            worker.interrupt();
            worker.join(2000);
            reactor.shutdown();
        }
    }
    private static String ms(long ns) { return String.format(Locale.ROOT, "%.3f", ns / 1000000.0); }
    private static String color(int id) { return id / 1000000 == 1 ? "#b8423a" : id / 1000000 == 2 ? "#286ba0" : "#278654"; }
    private static void write(Path output, ObservedReactor r, long release, int port, List<Integer> order) throws Exception {
        Files.createDirectories(output);
        StringBuilder csv = new StringBuilder("token,version,enqueue_before_ms,dequeue_ms,start_ms,end_ms,queue_wait_upper_bound_ms,execution_ms\n");
        for (Visit v : r.visits.values()) csv.append(v.id).append(",v00").append(v.id / 1000000).append(',')
                .append(ms(v.queued)).append(',').append(ms(v.dequeued)).append(',').append(ms(v.started)).append(',')
                .append(ms(v.ended)).append(',').append(ms(v.dequeued-v.queued)).append(',').append(ms(v.ended-v.started)).append('\n');
        Files.writeString(output.resolve("tokens.csv"), csv);
        StringBuilder depth = new StringBuilder("time_ms,waiting_tokens\n0,0\n");
        for (QueuePoint p : r.depth) depth.append(ms(p.time)).append(',').append(p.depth).append('\n');
        Files.writeString(output.resolve("queue.csv"), depth);
        String report = "PASS: priority overtaking with confirmed backlog\nQueue: packaged P1 EventReactor, loopback UDP " + port
                + "; one controlled consumer, no business rule execution\n"
                + "Arrivals behind blocker: " + ARRIVALS + "\nActual execution order: " + order
                + "\nEight tokens confirmed waiting before release at " + ms(release) + " ms.\n"
                + "No interruption of running work; zero rejected tokens; queue drained.\n"
                + "Enqueue timestamps are taken immediately BEFORE synchronized production admission. CSV queue wait includes admission overhead and is an upper bound. Graph waiting extends to execution start and includes dispatch overhead.\n";
        Files.writeString(output.resolve("report.txt"), report);
        double end = r.visits.get(3040000).ended / 1000000.0 * 1.06;
        double scale = 860.0 / end;
        StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1280\" height=\"770\" viewBox=\"0 0 1280 770\"><rect width=\"1280\" height=\"770\" fill=\"white\"/><g font-family=\"sans-serif\" fill=\"#253047\">"
                + "<text x=\"24\" y=\"34\" font-size=\"23\">Priority overtaking at one shared queue</text>"
                + "<text x=\"24\" y=\"61\" font-size=\"14\">Real P1 UDP scheduler · controlled single worker · rows in arrival order · absolute milliseconds</text>"
                + "<text x=\"24\" y=\"85\" font-size=\"13\">Grey: waiting (includes admission/dispatch overhead). Colour: controlled execution. Dashed line: blocker release.</text>");
        for (int tick = 0; tick <= 10; tick++) {
            double t = end * tick / 10, x = 210 + t * scale;
            svg.append(String.format(Locale.ROOT, "<path d=\"M%.2f 110V535\" stroke=\"#e4e8ee\"/><text x=\"%.2f\" y=\"555\" font-size=\"12\">%.0f</text>", x, x-8, t));
        }
        int row = 0;
        for (Visit v : r.visits.values()) {
            double y = 120 + row++ * 44, q = v.queued/1000000.0, start = v.started/1000000.0, finish = v.ended/1000000.0;
            svg.append(String.format(Locale.ROOT,
                    "<text x=\"24\" y=\"%.2f\" font-size=\"14\">v00%d / %d%s</text><rect x=\"%.2f\" y=\"%.2f\" width=\"%.2f\" height=\"23\" fill=\"#d6dce5\"/><rect x=\"%.2f\" y=\"%.2f\" width=\"%.2f\" height=\"23\" fill=\"%s\"><title>Token %d: wait upper bound %s ms; execution %s ms</title></rect><text x=\"1090\" y=\"%.2f\" font-size=\"12\">wait %s · work %s</text>",
                    y+17, v.id/1000000, v.id, v.id==BLOCKER?" (held)":"", 210+q*scale,y,(start-q)*scale,
                    210+start*scale,y,Math.max(1,(finish-start)*scale),color(v.id),v.id,ms(v.started-v.queued),ms(v.ended-v.started),y+17,ms(v.started-v.queued),ms(v.ended-v.started)));
        }
        double releaseX = 210 + release/1000000.0 * scale;
        svg.append(String.format(Locale.ROOT,"<path d=\"M%.2f 103V525\" stroke=\"#111827\" stroke-dasharray=\"5 4\"/><text x=\"210\" y=\"582\" font-size=\"14\">Milliseconds since reactor ready; blocker released at %s ms</text>",releaseX,ms(release)));
        svg.append("<text x=\"24\" y=\"620\" font-size=\"16\">Waiting queue occupancy</text>");
        StringBuilder path = new StringBuilder("M210 720");
        for (QueuePoint p : r.depth) {
            double lastX = 210+p.time/1000000.0*scale, lastY = 720-p.depth*9;
            path.append(String.format(Locale.ROOT," H%.2f V%.2f",lastX,lastY));
        }
        path.append(" H1070");
        svg.append("<text x=\"170\" y=\"653\" font-size=\"12\">8</text><text x=\"170\" y=\"722\" font-size=\"12\">0</text><path d=\"").append(path).append("\" fill=\"none\" stroke=\"#475569\" stroke-width=\"2\"/></g></svg>");
        Files.writeString(output.resolve("timeline.svg"), svg);
        Files.writeString(output.resolve("timeline.html"), "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Queue priority proof</title><body style=\"margin:0;font-family:sans-serif\">"
                + svg + "<pre style=\"margin:24px;white-space:pre-wrap\">" + report + "</pre></body></html>");
    }
}
