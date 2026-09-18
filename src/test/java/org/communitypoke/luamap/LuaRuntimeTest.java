package org.communitypoke.luamap;

import org.communitypoke.luamap.lua.LuaRuntime;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaRuntimeTest {

    @Test
    void sandboxRemovesDangerousLibraries() {
        Globals g = LuaRuntime.createSandbox();
        assertTrue(g.get("io").isnil(), "io should be removed");
        assertTrue(g.get("luajava").isnil(), "luajava should be removed");
        assertTrue(g.get("package").isnil(), "package should be removed");
        assertTrue(g.get("require").isnil(), "require should be removed");
        assertTrue(g.get("dofile").isnil(), "dofile should be removed");
        assertTrue(g.get("loadfile").isnil(), "loadfile should be removed");
        assertTrue(g.get("os").get("execute").isnil(), "os.execute should be removed");
        assertTrue(g.get("os").get("exit").isnil(), "os.exit should be removed");
        assertTrue(g.get("os").get("getenv").isnil(), "os.getenv should be removed");
    }

    @Test
    void sandboxKeepsUsefulLibraries() {
        Globals g = LuaRuntime.createSandbox();
        assertTrue(g.get("string").istable());
        assertTrue(g.get("table").istable());
        assertTrue(g.get("math").istable());
        assertTrue(g.get("os").get("time").isfunction(), "os.time should remain");
    }

    @Test
    void plainLuaRunsInSandbox() {
        Globals g = LuaRuntime.createSandbox();
        var result = g.load("return 1 + 2", "t").call();
        assertEquals(3, result.checkint());
        // lua-side check that the sandbox actually holds
        assertTrue(g.load("return io == nil and luajava == nil", "t").call().checkboolean());
    }

    @Test
    void loadRejectsBadSyntax() {
        Globals g = LuaRuntime.createSandbox();
        assertThrows(LuaError.class, () -> g.load("this is not lua").call());
    }
}
