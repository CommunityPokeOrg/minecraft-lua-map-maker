package org.communitypoke.luamap.lua;

import org.communitypoke.luamap.lua.api.LuaApi;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * LuaJ runtime: creates sandboxed globals and executes map scripts.
 *
 * <p>Sandbox: JSE standard globals minus anything that can reach the file
 * system, the OS, or Java reflection:
 * <ul>
 *   <li>{@code io}, {@code luajava}, {@code package} (and thus {@code require})
 *       and {@code dofile}/{@code loadfile} are removed.</li>
 *   <li>{@code os} keeps harmless functions (time/clock/date) but loses
 *       execute/exit/remove/rename/getenv/setenv/setlocale/tmpname.</li>
 * </ul>
 */
public final class LuaRuntime {

    private LuaRuntime() {
    }

    /** Standard globals with the dangerous surfaces removed. */
    public static Globals createSandbox() {
        Globals g = JsePlatform.standardGlobals();

        // Whole-library removals.
        g.set("io", LuaValue.NIL);
        g.set("luajava", LuaValue.NIL);
        g.set("package", LuaValue.NIL);

        // File/dynamic loading entry points from the base library.
        g.set("dofile", LuaValue.NIL);
        g.set("loadfile", LuaValue.NIL);
        g.set("require", LuaValue.NIL);

        // Keep os.time/clock/date/difftime; drop process & fs control.
        LuaValue os = g.get("os");
        if (!os.isnil()) {
            for (String name : new String[]{
                    "execute", "exit", "remove", "rename",
                    "getenv", "setenv", "setlocale", "tmpname"}) {
                os.set(name, LuaValue.NIL);
            }
        }
        return g;
    }

    /**
     * Run {@code code} in a fresh sandboxed environment with the map-making
     * API installed. Returns the chunk's return value as a string, or null.
     *
     * @throws LuaError on syntax or runtime errors (message carries line info)
     */
    public static String run(String code, String chunkName, LuaContext ctx) throws LuaError {
        Globals g = createSandbox();
        LuaApi.install(g, ctx);
        LuaValue chunk = g.load(code, chunkName);
        LuaValue result = chunk.call();
        return result.isnil() ? null : result.tojstring();
    }
}
