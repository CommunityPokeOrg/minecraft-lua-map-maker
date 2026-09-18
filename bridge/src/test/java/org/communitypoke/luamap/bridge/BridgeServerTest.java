package org.communitypoke.luamap.bridge;

import org.communitypoke.luamap.bridge.BridgeProtocol.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end over a real localhost socket with a fake executor. */
class BridgeServerTest {

    private BridgeServer server;
    private BridgeClient client;
    private final List<String> handled = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new BridgeServer(req -> {
            handled.add(req.op() + ":" + (req.code() != null ? req.code() : req.name()));
            return switch (req.op()) {
                case "status" -> Response.ok(req.id(), null, "ok");
                case "list" -> Response.ok(req.id(), "arena\nparkour", null);
                case "eval", "run" -> Response.ok(req.id(), "42", "ran it");
                default -> Response.error(req.id(), "unknown op " + req.op());
            };
        });
        server.start(0); // ephemeral port
        client = new BridgeClient("127.0.0.1", server.port());
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        server.close();
    }

    @Test
    void evalRoundTrip() throws Exception {
        Response r = client.request("eval", "return 41+1", null);
        assertTrue(r.ok());
        assertEquals("42", r.result());
        assertEquals("ran it", r.output());
        assertEquals("eval:return 41+1", handled.get(0));
    }

    @Test
    void runByName() throws Exception {
        Response r = client.request("run", null, "arena");
        assertTrue(r.ok());
        assertEquals("run:arena", handled.get(0));
    }

    @Test
    void listAndStatus() throws Exception {
        assertEquals("arena\nparkour", client.request("list", null, null).result());
        assertTrue(client.request("status", null, null).ok());
    }

    @Test
    void unknownOpReturnsError() throws Exception {
        Response r = client.request("teleport", null, null);
        assertFalse(r.ok());
        assertTrue(r.error().contains("unknown op"));
    }

    @Test
    void malformedLineReturnsErrorAndKeepsSession() throws Exception {
        client.request("status", null, null); // warm
        try (var s = new java.net.Socket("127.0.0.1", server.port());
             var w = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                     s.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8), true);
             var r = new java.io.BufferedReader(new java.io.InputStreamReader(
                     s.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            w.println("{garbage");
            Response bad = BridgeProtocol.decodeResponse(r.readLine());
            assertFalse(bad.ok());
            w.println(BridgeProtocol.encode(new BridgeProtocol.Request(9, "status", null, null)));
            Response good = BridgeProtocol.decodeResponse(r.readLine());
            assertTrue(good.ok());
            assertEquals(9, good.id());
        }
    }

    @Test
    void serverStopsCleanly() throws Exception {
        server.close();
        assertFalse(server.isRunning());
    }
}
