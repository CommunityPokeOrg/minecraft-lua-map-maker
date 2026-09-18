package org.communitypoke.luamap.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.communitypoke.luamap.ScriptLibrary;
import org.communitypoke.luamap.lua.LuaContext;
import org.communitypoke.luamap.lua.LuaRuntime;
import org.luaj.vm2.LuaError;

import java.io.IOException;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.command.CommandSource.suggestMatching;

/**
 * {@code /luamap} command tree:
 *
 * <pre>
 *   /luamap list          — scripts in &lt;gameDir&gt;/luamaps/
 *   /luamap dir           — print the scripts directory path
 *   /luamap run &lt;name&gt;   — execute luamaps/&lt;name&gt;.lua
 *   /luamap eval &lt;code&gt;  — evaluate a one-off Lua snippet
 * </pre>
 *
 * Requires permission level 2 (singleplayer cheats / server ops).
 */
public final class LuaMapCommand {

    private LuaMapCommand() {
    }

    public static void register(ScriptLibrary scripts) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("luamap")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(literal("list").executes(ctx -> list(ctx.getSource(), scripts)))
                        .then(literal("dir").executes(ctx -> dir(ctx.getSource(), scripts)))
                        .then(literal("run")
                                .then(argument("script", StringArgumentType.word())
                                        .suggests((ctx, builder) ->
                                                suggestMatching(scripts.list(), builder))
                                        .executes(ctx -> run(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "script"),
                                                scripts))))
                        .then(literal("eval")
                                .then(argument("code", StringArgumentType.greedyString())
                                        .executes(ctx -> eval(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "code")))))));
    }

    private static int list(ServerCommandSource src, ScriptLibrary scripts) {
        var names = scripts.list();
        if (names.isEmpty()) {
            src.sendFeedback(() -> Text.literal(
                    "No scripts — drop .lua files into " + scripts.directory()), false);
        } else {
            src.sendFeedback(() -> Text.literal(
                    "Scripts (" + names.size() + "): " + String.join(", ", names)), false);
        }
        return names.size();
    }

    private static int dir(ServerCommandSource src, ScriptLibrary scripts) {
        src.sendFeedback(() -> Text.literal("Scripts dir: " + scripts.directory().toAbsolutePath()), false);
        return SINGLE_SUCCESS;
    }

    private static int run(ServerCommandSource src, String name, ScriptLibrary scripts) {
        String code;
        try {
            code = scripts.read(name);
        } catch (IllegalArgumentException e) {
            src.sendError(Text.literal(e.getMessage()));
            return 0;
        } catch (IOException e) {
            src.sendError(Text.literal("Could not read script '" + name + "': " + e.getMessage()));
            return 0;
        }
        return execute(src, code, "@" + ScriptLibrary.normalizeName(name));
    }

    private static int eval(ServerCommandSource src, String code) {
        return execute(src, code, "eval");
    }

    private static int execute(ServerCommandSource src, String code, String chunkName) {
        try {
            String result = LuaRuntime.run(code, chunkName, new LuaContext(src));
            if (result != null) {
                src.sendFeedback(() -> Text.literal("= " + result), false);
            }
            return SINGLE_SUCCESS;
        } catch (LuaError e) {
            src.sendError(Text.literal("Lua error: " + e.getMessage()));
            return 0;
        } catch (RuntimeException e) {
            src.sendError(Text.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
}
