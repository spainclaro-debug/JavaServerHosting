package dev.javahost.samples.generic;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Tiny, dependency-free example of a "generic" (non-Minecraft) Java server for
 * JavaHost. It exists so the app can be tested end-to-end with a real JAR that
 * is not Minecraft.
 *
 * <p>Deliberately uses only the JDK ({@code com.sun.net.httpserver}) and plain
 * {@code main(String[])}. This is original sample code, not copied from any
 * other project.
 *
 * <p>Usage: {@code java -jar generic-server.jar --port 18081}
 *
 * <p>Console output is line-oriented so JavaHost's console parser and its
 * "listening on port" / ready heuristics can be exercised realistically.
 */
public final class GenericServer {

    private static final String TAG = "[sample-server]";

    private final int port;
    private final Instant startedAt = Instant.now();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile HttpServer server;

    private GenericServer(int port) {
        this.port = port;
    }

    public static void main(String[] args) {
        int port = 18080;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (("--port".equals(arg) || "-p".equals(arg)) && i + 1 < args.length) {
                port = parsePort(args[++i]);
            } else if (arg.startsWith("--port=")) {
                port = parsePort(arg.substring("--port=".length()));
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println(TAG + " Usage: java -jar generic-server.jar --port <1-65535>");
                return;
            } else {
                System.out.println(TAG + " WARN ignoring unknown argument: " + arg);
            }
        }
        try {
            new GenericServer(port).run();
        } catch (Exception e) {
            System.err.println(TAG + " ERROR failed to start: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static int parsePort(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value > 65535) {
                throw new NumberFormatException("port out of range");
            }
            return value;
        } catch (NumberFormatException e) {
            System.err.println(TAG + " ERROR invalid --port value: " + raw);
            System.exit(2);
            return -1; // unreachable
        }
    }

    private void run() throws IOException, InterruptedException {
        System.out.println(TAG + " JavaHost sample generic server starting");
        System.out.println(TAG + " Java: " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        System.out.println(TAG + " PID: " + ProcessHandle.current().pid());
        System.out.println(TAG + " Binding port " + port);

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handleRoot);
        server.createContext("/health", this::handleHealth);
        server.createContext("/status", this::handleStatus);
        server.createContext("/shutdown", this::handleShutdown);
        server.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "sample-http");
            thread.setDaemon(true);
            return thread;
        }));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println(TAG + " Shutdown requested");
            stopInternal();
            stopped.countDown();
        }, "sample-shutdown"));

        server.start();
        System.out.println(TAG + " Listening on port " + port);
        System.out.println(TAG + " Ready. Try: curl http://127.0.0.1:" + port + "/health");
        stopped.await();
        System.out.println(TAG + " Stopped");
    }

    private void stopInternal() {
        HttpServer current = server;
        if (current != null) {
            current.stop(1);
            server = null;
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        String body = "{\"status\":\"ok\",\"server\":\"javahost-sample\","
                + "\"uptimeSeconds\":" + (Instant.now().getEpochSecond() - startedAt.getEpochSecond()) + "}";
        respond(exchange, 200, "application/json", body);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "text/plain", "ok");
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "text/plain",
                "state=running port=" + port + " uptimeSeconds="
                        + (Instant.now().getEpochSecond() - startedAt.getEpochSecond()));
    }

    private void handleShutdown(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "text/plain", "shutting down");
        System.out.println(TAG + " Shutdown endpoint called");
        stopInternal();
        stopped.countDown();
    }

    private void respond(HttpExchange exchange, int code, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        System.out.println(TAG + " " + exchange.getRequestMethod() + " "
                + exchange.getRequestURI().getPath() + " -> " + code);
    }
}
