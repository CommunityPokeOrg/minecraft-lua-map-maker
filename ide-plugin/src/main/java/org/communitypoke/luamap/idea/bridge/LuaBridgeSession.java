package org.communitypoke.luamap.idea.bridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Connection lifecycle + polling controller for the LuaBridge tool window.
 *
 * <p>IntelliJ-free by design: every socket operation runs on a single
 * background daemon thread, and state changes are delivered to a
 * {@link Listener} through a user-supplied {@link Consumer} dispatcher (the
 * Swing layer passes {@code SwingUtilities::invokeLater}, tests pass a
 * synchronous dispatcher). The UI never does socket I/O and the controller
 * never does UI.
 *
 * <p>Lifecycle: {@link #connect(String, int)} is idempotent while connected;
 * {@link #disconnect()} stops polling and closes the socket;
 * {@link #close()} additionally terminates the executor (called on dispose).
 * With auto-reconnect enabled a failed poll schedules one reconnect attempt
 * per {@link #RECONNECT_DELAY_MS} until it succeeds or the user disconnects.
 */
public final class LuaBridgeSession implements AutoCloseable {

    public enum State {DISCONNECTED, CONNECTING, CONNECTED, ERROR}

    /** One NPC row, parsed from {@link #NPC_LIST_LUA}. */
    public record NpcInfo(String name, double x, double y, double z) {
    }

    /** Snapshot of one {@code status} reply. */
    public record StatusInfo(String raw, int scriptCount, int npcCount, String world) {
    }

    public interface Listener {
        /** Invoked via the dispatcher — on the EDT for the Swing layer. */
        void onStateChanged(State state, String detail);
    }

    /**
     * Lua snippet that serializes {@code npc.list()} into one
     * {@code name,x,y,z} CSV line per NPC (LuaJ has no JSON module).
     */
    public static final String NPC_LIST_LUA =
            "local out = {}\n"
                    + "for n,p in pairs(npc.list()) do\n"
                    + "  out[#out+1] = string.format(\"%s,%.2f,%.2f,%.2f\", n, p.x, p.y, p.z)\n"
                    + "end\n"
                    + "table.sort(out)\n"
                    + "return table.concat(out, \"\\n\")\n";

    /** Interval for automatic status + NPC refresh while connected. */
    public static final long POLL_INTERVAL_MS = 2_000;
    /** Delay before an auto-reconnect attempt after a connection drop. */
    public static final long RECONNECT_DELAY_MS = 1_500;

    private final ScheduledExecutorService executor;
    private final Listener listener;
    private final Consumer<Runnable> dispatcher;

    private volatile State state = State.DISCONNECTED;
    private volatile String detail = "not connected";
    private volatile LuaBridgeClient client;
    private volatile ScheduledFuture<?> pollTask;
    private volatile boolean autoReconnect = true;
    private volatile String host = "127.0.0.1";
    private volatile int port = LuaBridgeClient.DEFAULT_PORT;
    /** Optional observer invoked (via dispatcher) after each successful poll. */
    private volatile Consumer<StatusInfo> pollListener;

    public LuaBridgeSession(Listener listener, Consumer<Runnable> dispatcher) {
        this(listener, dispatcher, defaultExecutor());
    }

    /** Test seam: lets tests inject an executor they control. */
    public LuaBridgeSession(Listener listener, Consumer<Runnable> dispatcher,
                            ScheduledExecutorService executor) {
        this.listener = Objects.requireNonNull(listener);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.executor = executor;
    }

    private static ScheduledExecutorService defaultExecutor() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "luamap-bridge-session");
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadScheduledExecutor(tf);
    }

    public State state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean autoReconnect() {
        return autoReconnect;
    }

    public void setAutoReconnect(boolean enabled) {
        autoReconnect = enabled;
    }

    /** Register a listener called with the latest StatusInfo on every poll. */
    public void setPollListener(Consumer<StatusInfo> listener) {
        this.pollListener = listener;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    /** Connect (or reconnect to new endpoint). Any failure lands in ERROR. */
    public void connect(String host, int port) {
        this.host = host;
        this.port = port;
        submit(() -> doConnect());
    }

    /** Manual reconnect against the last used endpoint. */
    public void reconnect() {
        submit(this::doConnect);
    }

    /** User-initiated disconnect: no auto-reconnect afterwards. */
    public void disconnect() {
        submit(() -> {
            stopPolling();
            closeClient();
            setState(State.DISCONNECTED, "disconnected");
        });
    }

    /**
     * Run one request on the session thread. {@code handler} receives the
     * open client; a failure puts the session in ERROR and starts
     * auto-reconnect. Returns via the executor — never call from EDT.
     */
    public void request(SessionRequest req) {
        submit(() -> {
            LuaBridgeClient c = client;
            if (c == null) {
                req.onError("not connected");
                return;
            }
            try {
                req.run(c);
            } catch (IOException e) {
                onFailure("request failed: " + e.getMessage());
                req.onError(e.getMessage());
            }
        });
    }

    /** Poll {@code status} + the NPC table; safe to call when disconnected. */
    public void refresh(Consumer<StatusInfo> onStatus, Consumer<List<NpcInfo>> onNpcs) {
        request(new SessionRequest() {
            @Override
            public void run(LuaBridgeClient c) throws IOException {
                StatusInfo st = parseStatus(c.status());
                if (onStatus != null) {
                    post(() -> onStatus.accept(st));
                }
                if (onNpcs != null) {
                    List<NpcInfo> npcs = parseNpcs(c.eval(NPC_LIST_LUA));
                    post(() -> onNpcs.accept(npcs));
                }
            }

            @Override
            public void onError(String message) {
                // state/detail already updated by onFailure
            }
        });
    }

    /** Convenience: eval {@code world.getblock(x, y, z)} for the UI. */
    public void queryBlock(int x, int y, int z, Consumer<String> onResult) {
        request(new SessionRequest() {
            @Override
            public void run(LuaBridgeClient c) throws IOException {
                LuaBridgeClient.Reply r =
                        c.eval("return tostring(world.getblock(" + x + "," + y + "," + z + "))");
                post(() -> onResult.accept(replyText(r)));
            }

            @Override
            public void onError(String message) {
                post(() -> onResult.accept("error: " + message));
            }
        });
    }

    /** Convenience: run a named script, reporting output/result. */
    public void runScript(String name, Consumer<String> onResult) {
        request(new SessionRequest() {
            @Override
            public void run(LuaBridgeClient c) throws IOException {
                String text = replyText(c.run(name));
                post(() -> onResult.accept(text));
            }

            @Override
            public void onError(String message) {
                post(() -> onResult.accept("error: " + message));
            }
        });
    }

    /** Convenience: fetch the script list ({@code list} op). */
    public void listScripts(Consumer<List<String>> onResult) {
        request(new SessionRequest() {
            @Override
            public void run(LuaBridgeClient c) throws IOException {
                LuaBridgeClient.Reply r = c.call("list", null, null);
                List<String> names = new ArrayList<>();
                if (r.ok() && r.result() != null) {
                    for (String line : r.result().split("\n")) {
                        if (!line.isBlank()) {
                            names.add(line.trim());
                        }
                    }
                }
                post(() -> onResult.accept(names));
            }

            @Override
            public void onError(String message) {
                post(() -> onResult.accept(List.of()));
            }
        });
    }

    /** Convenience: eval arbitrary code, reporting output/result. */
    public void eval(String code, Consumer<String> onResult) {
        request(new SessionRequest() {
            @Override
            public void run(LuaBridgeClient c) throws IOException {
                String text = replyText(c.eval(code));
                post(() -> onResult.accept(text));
            }

            @Override
            public void onError(String message) {
                post(() -> onResult.accept("error: " + message));
            }
        });
    }

    private void doConnect() {
        stopPolling();
        closeClient();
        setState(State.CONNECTING, "connecting to " + host + ":" + port);
        try {
            client = new LuaBridgeClient(host, port);
            StatusInfo st = parseStatus(client.status());
            setState(State.CONNECTED, st.raw());
            pollTask = executor.scheduleWithFixedDelay(() -> {
                try {
                    pollOnce();
                } catch (Throwable ignored) {
                    // pollOnce already routes failures via onFailure
                }
            }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            closeClient();
            setState(State.ERROR, "connect failed: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void pollOnce() {
        LuaBridgeClient c = client;
        if (c == null) {
            return;
        }
        try {
            StatusInfo st = parseStatus(c.status());
            setState(State.CONNECTED, st.raw());
            Consumer<StatusInfo> pl = pollListener;
            if (pl != null) {
                post(() -> pl.accept(st));
            }
        } catch (IOException e) {
            onFailure("lost connection: " + e.getMessage());
        }
    }

    private void onFailure(String message) {
        stopPolling();
        closeClient();
        setState(State.ERROR, message);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!autoReconnect || executor.isShutdown()) {
            return;
        }
        executor.schedule(() -> {
            if (state == State.ERROR && autoReconnect) {
                doConnect();
            }
        }, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPolling() {
        ScheduledFuture<?> t = pollTask;
        pollTask = null;
        if (t != null) {
            t.cancel(false);
        }
    }

    private void closeClient() {
        LuaBridgeClient c = client;
        client = null;
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void setState(State s, String d) {
        state = s;
        detail = d;
        post(() -> listener.onStateChanged(s, d));
    }

    private void post(Runnable r) {
        dispatcher.accept(r);
    }

    private void submit(Runnable r) {
        if (executor.isShutdown()) {
            return;
        }
        executor.execute(() -> {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    public void close() {
        stopPolling();
        closeClient();
        executor.shutdownNow();
    }

    // ---------- parsing (deterministic, pure) ----------

    static StatusInfo parseStatus(LuaBridgeClient.Reply r) {
        String raw = r.ok() ? (r.output() != null ? r.output() : "ok")
                : "error: " + r.error();
        int scripts = -1, npcs = -1;
        String world = "";
        for (String part : raw.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) {
                try {
                    switch (kv[0].trim()) {
                        case "scripts" -> scripts = Integer.parseInt(kv[1].trim());
                        case "npcs" -> npcs = Integer.parseInt(kv[1].trim());
                        case "world" -> world = kv[1].trim();
                        default -> { }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new StatusInfo(raw, scripts, npcs, world);
    }

    static List<NpcInfo> parseNpcs(LuaBridgeClient.Reply r) {
        List<NpcInfo> out = new ArrayList<>();
        if (!r.ok() || r.result() == null || r.result().isBlank()
                || "nil".equals(r.result().trim())) {
            return out;
        }
        for (String line : r.result().split("\n")) {
            String[] f = line.trim().split(",");
            if (f.length == 4) {
                try {
                    out.add(new NpcInfo(f[0], Double.parseDouble(f[1]),
                            Double.parseDouble(f[2]), Double.parseDouble(f[3])));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    static String replyText(LuaBridgeClient.Reply r) {
        if (!r.ok()) {
            return "error: " + r.error();
        }
        StringBuilder sb = new StringBuilder();
        if (r.output() != null && !r.output().isBlank()) {
            sb.append(r.output());
        }
        if (r.result() != null && !r.result().isBlank() && !"nil".equals(r.result().trim())) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("=> ").append(r.result());
        }
        return sb.length() == 0 ? "ok" : sb.toString();
    }

    /** One bridge request executed on the session thread. */
    public interface SessionRequest {
        void run(LuaBridgeClient client) throws IOException;

        void onError(String message);
    }
}
