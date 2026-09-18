package org.communitypoke.luamap.bridge;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.communitypoke.luamap.LuaMapMakerMod;
import org.communitypoke.luamap.ScriptLibrary;
import org.communitypoke.luamap.lua.LuaContext;
import org.communitypoke.luamap.lua.LuaRuntime;
import org.communitypoke.luamap.npc.NpcManager;
import org.luaj.vm2.Globals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * LuaBridge: a localhost-only companion/debug bridge that exposes script
 * eval/run/list/reload/status over a newline-delimited-JSON socket (see
 * {@code bridge/} module and docs/luabridge.md). Intended for singleplayer
 * live-edit workflows — the IntelliJ plugin talks to it.
 *
 * <p>Disabled by default. Enable with either:
 * <ul>
 *   <li>JVM flag {@code -Dluamap.bridge.port=25575} (the launcher's
 *       {@code --bridgePort} sets this), or</li>
 *   <li>{@code port=25575} in {@code <gameDir>/luamap-bridge.properties}.</li>
 * </ul>
 *
 * <p>Safety: binds loopback only and shares the Lua script sandbox — a local
 * process can only do what {@code /luamap eval} can do, nothing more.
 * Requests are dispatched onto the server thread, so scripts stay synchronous
 * and safe.
 */
public final class LuaBridgeService {

    private static final int TIMEOUT_SECONDS = 60;

    private LuaBridgeService() {
    }

    /** Hook server lifecycle: start the bridge on SERVER_STARTED if enabled. */
    public static void register(ScriptLibrary scripts) {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            int port = readPort();
            if (port <= 0) {
                return;
            }
            BridgeServer bridge = new BridgeServer(req -> dispatch(server, scripts, req));
            try {
                bridge.start(port);
                LuaMapMakerMod.LOGGER.info("LuaBridge listening on 127.0.0.1:{} — eval/run/list/reload/status",
                        bridge.port());
            } catch (IOException e) {
                LuaMapMakerMod.LOGGER.warn("LuaBridge failed to bind port {}", port, e);
                return;
            }
            ServerLifecycleEvents.SERVER_STOPPED.register(s -> bridge.close());
        });
    }

    /**
     * Port resolution: system property first, then
     * {@code <gameDir>/luamap-bridge.properties}. {@code <= 0} means disabled.
     */
    static int readPort() {
        String prop = System.getProperty("luamap.bridge.port");
        if (prop != null && !prop.isBlank()) {
            return Integer.parseInt(prop.trim());
        }
        Path cfg = FabricLoader.getInstance().getGameDir().resolve("luamap-bridge.properties");
        if (Files.isRegularFile(cfg)) {
            try (InputStream in = Files.newInputStream(cfg)) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("port", "0").trim();
                return v.isEmpty() ? 0 : Integer.parseInt(v);
            } catch (IOException | NumberFormatException e) {
                LuaMapMakerMod.LOGGER.warn("Bad {}: {}", cfg, e.toString());
            }
        }
        return 0;
    }

    /** Marshal a request onto the server thread and wait for the result. */
    private static BridgeProtocol.Response dispatch(
            MinecraftServer server, ScriptLibrary scripts, BridgeProtocol.Request req) {
        CompletableFuture<BridgeProtocol.Response> f = new CompletableFuture<>();
        server.execute(() -> f.complete(handle(server, scripts, req)));
        try {
            return f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return BridgeProtocol.Response.error(req.id(), "timed out: " + e.getMessage());
        }
    }

    private static BridgeProtocol.Response handle(
            MinecraftServer server, ScriptLibrary scripts, BridgeProtocol.Request req) {
        try {
            return switch (req.op()) {
                case BridgeProtocol.OP_STATUS -> BridgeProtocol.Response.ok(req.id(), null,
                        "ok; scripts=" + scripts.list().size()
                                + "; npcs=" + NpcManager.all().size()
                                + "; world=" + server.getOverworld().getRegistryKey().getValue());
                case BridgeProtocol.OP_LIST -> BridgeProtocol.Response.ok(req.id(),
                        String.join("\n", scripts.list()), null);
                case BridgeProtocol.OP_EVAL -> eval(server, req, require(req.code(), "code"), "eval");
                case BridgeProtocol.OP_RUN -> {
                    String name = require(req.name(), "name");
                    yield eval(server, req, scripts.read(name), name);
                }
                case BridgeProtocol.OP_RELOAD -> {
                    // Reload = re-read from disk + syntax check WITHOUT executing.
                    String name = require(req.name(), "name");
                    String code = scripts.read(name);
                    Globals g = LuaRuntime.createSandbox();
                    g.load(code, name); // compiles; throws LuaError on bad syntax
                    yield BridgeProtocol.Response.ok(req.id(),
                            "ok; " + code.length() + " bytes", null);
                }
                default -> BridgeProtocol.Response.error(req.id(), "unknown op '" + req.op() + "'");
            };
        } catch (Exception e) {
            return BridgeProtocol.Response.error(req.id(), String.valueOf(e.getMessage()));
        }
    }

    private static BridgeProtocol.Response eval(
            MinecraftServer server, BridgeProtocol.Request req, String code, String chunkName) {
        List<String> lines = new ArrayList<>();
        ServerWorld world = server.getOverworld();
        LuaContext ctx = new LuaContext(world, lines::add);
        String result = LuaRuntime.run(code, chunkName, ctx);
        return BridgeProtocol.Response.ok(req.id(), result,
                lines.isEmpty() ? null : String.join("\n", lines));
    }

    private static String require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing '" + field + "'");
        }
        return v;
    }
}
