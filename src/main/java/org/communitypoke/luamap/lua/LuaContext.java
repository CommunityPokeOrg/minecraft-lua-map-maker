package org.communitypoke.luamap.lua;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.communitypoke.luamap.LuaMapMakerMod;

import java.util.function.Consumer;

/**
 * Everything a running Lua script can touch: the world it's building in, the
 * player who invoked it (if any), and where its output should go.
 */
public final class LuaContext {
    private final ServerCommandSource source; // may be null when invoked outside a command
    private final ServerWorld world;
    private final Consumer<String> sink;

    public LuaContext(ServerCommandSource source) {
        this.source = source;
        this.world = source.getWorld();
        this.sink = null;
    }

    /** Headless/testing context: no command source, output goes to a sink. */
    public LuaContext(ServerWorld world, Consumer<String> sink) {
        this.source = null;
        this.world = world;
        this.sink = sink;
    }

    public ServerWorld world() {
        return world;
    }

    /** The invoking player, or null when run from console / command block / etc. */
    public ServerPlayerEntity playerOrNull() {
        return source == null ? null : source.getPlayer();
    }

    /** Route a line of script output to chat (command) or the logger/sink. */
    public void out(String message) {
        if (source != null) {
            source.sendFeedback(() -> Text.literal(message), false);
        } else if (sink != null) {
            sink.accept(message);
        } else {
            LuaMapMakerMod.LOGGER.info("[luamap] {}", message);
        }
    }

    public void error(String message) {
        if (source != null) {
            source.sendError(Text.literal(message));
        } else if (sink != null) {
            sink.accept("[error] " + message);
        } else {
            LuaMapMakerMod.LOGGER.error("[luamap] {}", message);
        }
    }
}
