package org.communitypoke.luamap.idea.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Loopback NDJSON server impersonating LuaBridge for tests. Answers each
 * request line through a pluggable responder; {@link #dropConnections}
 * simulates a server going away; {@link #requests} records every request.
 */
final class MockBridgeServer implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptor;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<JsonObject> requests =
            new ConcurrentLinkedQueue<>();
    private final CountDownLatch accepted = new CountDownLatch(1);
    private volatile Function<JsonObject, JsonObject> responder;
    private volatile boolean running = true;

    MockBridgeServer() throws IOException {
        server = new ServerSocket(0);
        responder = MockBridgeServer::defaultReply;
        acceptor = new Thread(this::acceptLoop, "mock-bridge-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    void setResponder(Function<JsonObject, JsonObject> r) {
        responder = r;
    }

    /** All requests received so far. */
    List<JsonObject> requests() {
        return List.copyOf(requests);
    }

    /** Wait for at least one client connection. */
    boolean awaitClient(long ms) throws InterruptedException {
        return accepted.await(ms, TimeUnit.MILLISECONDS);
    }

    /** Forcibly close every accepted client socket (simulates a crash). */
    void dropConnections() throws IOException {
        for (Socket s : clients) {
            s.close();
        }
        clients.clear();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                clients.add(s);
                accepted.countDown();
                Thread reader = new Thread(() -> serve(s), "mock-bridge-reader");
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                if (running) {
                    throw new RuntimeException(e);
                }
                return;
            }
        }
    }

    private void serve(Socket s) {
        try (s;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(
                     s.getOutputStream(), StandardCharsets.UTF_8), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject req = JsonParser.parseString(line).getAsJsonObject();
                requests.add(req);
                JsonObject resp = responder.apply(req);
                if (resp != null) {
                    out.println(resp);
                }
            }
        } catch (IOException ignored) {
            // client hung up or we dropped it — normal in these tests
        } finally {
            clients.remove(s);
        }
    }

    private static JsonObject defaultReply(JsonObject req) {
        long id = req.get("id").getAsLong();
        return switch (req.get("op").getAsString()) {
            case "status" -> reply(id, true, null,
                    "ok; scripts=3; npcs=2; world=minecraft:overworld");
            case "list" -> reply(id, true, "hello\narena\nparkour", null);
            case "eval" -> {
                String code = req.has("code") ? req.get("code").getAsString() : "";
                if (code.contains("npc.list")) {
                    yield reply(id, true,
                            "Steve,10.00,64.00,-5.00\nAlex,-3.50,70.25,8.00", null);
                }
                if (code.contains("world.getblock")) {
                    yield reply(id, true, "minecraft:stone", null);
                }
                yield reply(id, true, "eval-result", "chat line one");
            }
            case "run" -> reply(id, true, null, "ran " +
                    (req.has("name") ? req.get("name").getAsString() : "?"));
            case "reload" -> reply(id, true, "ok; 120 bytes", null);
            default -> reply(id, false, null, "unknown op '" +
                    req.get("op").getAsString() + "'");
        };
    }

    static JsonObject reply(long id, boolean ok, String result, String output) {
        JsonObject r = new JsonObject();
        r.addProperty("v", 1);
        r.addProperty("id", id);
        r.addProperty("ok", ok);
        if (result != null) {
            r.addProperty("result", result);
        }
        if (output != null) {
            r.addProperty("output", output);
        }
        if (!ok) {
            r.addProperty("error", output == null ? "error" : output);
        }
        return r;
    }

    @Override
    public void close() throws IOException {
        running = false;
        dropConnections();
        server.close();
    }
}
