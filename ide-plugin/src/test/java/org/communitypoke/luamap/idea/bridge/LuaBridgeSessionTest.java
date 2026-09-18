package org.communitypoke.luamap.idea.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loopback integration tests for {@link LuaBridgeSession} and
 * {@link LuaBridgeClient} against {@link MockBridgeServer}.
 */
class LuaBridgeSessionTest {

    private MockBridgeServer server;
    private List<LuaBridgeSession.State> states;
    private List<String> details;
    private LuaBridgeSession session;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockBridgeServer();
        states = new CopyOnWriteArrayList<>();
        details = new CopyOnWriteArrayList<>();
        // Synchronous dispatcher — tests assert directly off the session
        // thread; production passes SwingUtilities::invokeLater.
        session = new LuaBridgeSession(
                (s, d) -> {
                    states.add(s);
                    details.add(d);
                },
                Runnable::run);
    }

    @AfterEach
    void tearDown() throws Exception {
        session.close();
        server.close();
    }

    private void awaitState(LuaBridgeSession.State wanted, long ms)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (session.state() == wanted) {
                return;
            }
            Thread.sleep(10);
        }
        // fall through and let the assertion print real state
        assertEquals(wanted, session.state(),
                "states seen: " + states + " details: " + details);
    }

    @Test
    void connectTransitionsToConnectedAndParsesStatus() throws Exception {
        session.connect("127.0.0.1", server.port());
        awaitState(LuaBridgeSession.State.CONNECTED, 5_000);
        assertTrue(states.contains(LuaBridgeSession.State.CONNECTING));
        assertTrue(session.isConnected());
        assertEquals("ok; scripts=3; npcs=2; world=minecraft:overworld",
                session.detail());
        assertTrue(server.awaitClient(1_000));
    }

    @Test
    void connectToDeadPortEndsInError() throws Exception {
        // nothing listening on 9 (discard port by convention, unlikely used)
        session.setAutoReconnect(false);
        session.connect("127.0.0.1", 9);
        awaitState(LuaBridgeSession.State.ERROR, 10_000);
        assertFalse(session.isConnected());
        assertTrue(session.detail().startsWith("connect failed:"));
    }

    @Test
    void disconnectStaysDisconnected() throws Exception {
        session.connect("127.0.0.1", server.port());
        awaitState(LuaBridgeSession.State.CONNECTED, 5_000);
        session.disconnect();
        awaitState(LuaBridgeSession.State.DISCONNECTED, 5_000);
        assertEquals("disconnected", session.detail());
        // Give any stray reconnect a moment — none must fire.
        Thread.sleep(200);
        assertEquals(LuaBridgeSession.State.DISCONNECTED, session.state());
    }

    @Test
    void droppedConnectionTriggersAutoReconnect() throws Exception {
        session.connect("127.0.0.1", server.port());
        awaitState(LuaBridgeSession.State.CONNECTED, 5_000);
        server.dropConnections();
        // poll sees the dead socket -> ERROR -> auto reconnect -> CONNECTED
        awaitState(LuaBridgeSession.State.ERROR, 10_000);
        awaitState(LuaBridgeSession.State.CONNECTED, 10_000);
        assertTrue(session.isConnected());
    }

    @Test
    void droppedConnectionStaysErrorWhenAutoReconnectOff() throws Exception {
        session.connect("127.0.0.1", server.port());
        awaitState(LuaBridgeSession.State.CONNECTED, 5_000);
        session.setAutoReconnect(false);
        server.dropConnections();
        awaitState(LuaBridgeSession.State.ERROR, 10_000);
        Thread.sleep(LuaBridgeSession.RECONNECT_DELAY_MS + 400);
        assertEquals(LuaBridgeSession.State.ERROR, session.state());
    }

    @Test
    void refreshParsesNpcsAndStatus() throws Exception {
        session.connect("127.0.0.1", server.port());
        awaitState(LuaBridgeSession.State.CONNECTED, 5_000);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<LuaBridgeSession.StatusInfo> status = new AtomicReference<>();
        AtomicReference<List<LuaBridgeSession.NpcInfo>> npcs = new AtomicReference<>();
        session.refresh(st -> {
            status.set(st);
            done.countDown();
        }, npcs::set);
        assertTrue(done.await(5_000, TimeUnit.SECONDS));
        Thread.sleep(100); // npc callback is a separate post
        assertEquals(3, status.get().scriptCount());
        assertEquals(2, status.get().npcCount());
        assertEquals("minecraft:overworld", status.get().world());
        assertEquals(List.of(
                        new LuaBridgeSession.NpcInfo("Steve", 10.0, 64.0, -5.0),
                        new LuaBridgeSession.NpcInfo("Alex", -3.5, 70.25, 8.0)),
                npcs.get());
    }

    @Test
    void queryBlockAndEvalRoundTrip() throws Exception {
        session.connect("127.0.0.1", server.port());
        awaitState(LuaBridgeSession.State.CONNECTED, 5_000);

        AtomicReference<String> block = new AtomicReference<>();
        CountDownLatch l1 = new CountDownLatch(1);
        session.queryBlock(0, 64, 0, s -> {
            block.set(s);
            l1.countDown();
        });
        assertTrue(l1.await(5_000, TimeUnit.SECONDS));
        assertEquals("=> minecraft:stone", block.get());

        AtomicReference<String> out = new AtomicReference<>();
        CountDownLatch l2 = new CountDownLatch(1);
        session.eval("return 1 + 1", s -> {
            out.set(s);
            l2.countDown();
        });
        assertTrue(l2.await(5_000, TimeUnit.SECONDS));
        assertTrue(out.get().contains("chat line one"));
        assertTrue(out.get().contains("=> eval-result"));
    }

    @Test
    void listScriptsParsesResult() throws Exception {
        session.connect("127.0.0.1", server.port());
        awaitState(LuaBridgeSession.State.CONNECTED, 5_000);
        AtomicReference<List<String>> scripts = new AtomicReference<>();
        CountDownLatch l = new CountDownLatch(1);
        session.listScripts(s -> {
            scripts.set(s);
            l.countDown();
        });
        assertTrue(l.await(5_000, TimeUnit.SECONDS));
        assertEquals(List.of("hello", "arena", "parkour"), scripts.get());
    }

    @Test
    void errorReplySurfacesMessage() throws Exception {
        server.setResponder(req -> MockBridgeServer.reply(
                req.get("id").getAsLong(), false, null, "boom"));
        session.connect("127.0.0.1", server.port());
        // connect calls status(); an error reply still leaves the socket open
        awaitState(LuaBridgeSession.State.CONNECTED, 5_000);
        AtomicReference<String> out = new AtomicReference<>();
        CountDownLatch l = new CountDownLatch(1);
        session.eval("broken()", s -> {
            out.set(s);
            l.countDown();
        });
        assertTrue(l.await(5_000, TimeUnit.SECONDS));
        assertEquals("error: boom", out.get());
    }

    @Test
    void requestsAreSentAsNdjsonProtocolV1() throws Exception {
        try (LuaBridgeClient c = new LuaBridgeClient("127.0.0.1", server.port())) {
            LuaBridgeClient.Reply r = c.eval("return 1");
            assertTrue(r.ok());
        }
        List<com.google.gson.JsonObject> reqs = server.requests();
        assertEquals(1, reqs.size());
        var req = reqs.get(0);
        assertEquals(1, req.get("v").getAsInt());
        assertEquals("eval", req.get("op").getAsString());
        assertEquals("return 1", req.get("code").getAsString());
        assertTrue(req.get("id").getAsLong() > 0);
    }

    @Test
    void sessionNeverBlocksCallerThread() throws Exception {
        // The caller thread (EDT in production) must return immediately —
        // measure that connect() itself does no socket I/O by pointing at a
        // slow-to-respond endpoint and asserting prompt return.
        long start = System.currentTimeMillis();
        session.connect("10.255.255.1", 65000); // unroutable; connect() must not block
        assertTrue(System.currentTimeMillis() - start < 1_000,
                "connect() blocked the caller thread");
    }
}
