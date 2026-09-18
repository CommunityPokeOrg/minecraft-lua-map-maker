package org.communitypoke.luamap.lua.api;

import org.communitypoke.luamap.lua.LuaContext;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * Installs the map-making API surface into a sandboxed {@link Globals}:
 *
 * <pre>
 *   world   — block editing, spawn, time, weather
 *   player  — invoking player's position, teleport, inventory (nil-safe)
 *   chat(s) — send a chat message / command feedback
 *   log(s)  — same as chat (alias); print(s) also routed here
 * </pre>
 */
public final class LuaApi {

    private LuaApi() {
    }

    public static void install(Globals g, LuaContext ctx) {
        g.set("world", new WorldApi(ctx));
        g.set("player", new PlayerApi(ctx));

        g.set("chat", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ctx.out(join(args));
                return NIL;
            }
        });
        g.set("log", g.get("chat"));
        g.set("print", g.get("chat"));
    }

    static String join(Varargs args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= args.narg(); i++) {
            if (i > 1) {
                sb.append('\t');
            }
            sb.append(args.arg(i).tojstring());
        }
        return sb.toString();
    }
}
