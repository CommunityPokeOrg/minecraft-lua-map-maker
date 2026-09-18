package org.communitypoke.luamap;

import org.communitypoke.luamap.lua.LuaRuntime;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless harness for luamaps/parkour.lua: injects a fake world/player into
 * the real sandboxed globals and runs the actual script end-to-end.
 */
class ParkourScriptTest {

    private static final String AIR = "minecraft:air";
    private static final String MARKER = "minecraft:lodestone";
    private static final String ODOMETER = "minecraft:bedrock";
    private static final int MARK_Y = 200;
    private static final int INDEX_BASE = 201;

    /** Block simulation: flat dirt below y=64, air above, edits tracked. */
    static final class FakeWorld {
        final Map<String, String> blocks = new HashMap<>();
        int setCalls, fillCalls, fills;
        final List<String> chat = new ArrayList<>();

        private static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        String get(int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), y <= 63 ? "minecraft:dirt" : AIR);
        }

        void set(int x, int y, int z, String spec) {
            setCalls++;
            blocks.put(key(x, y, z), spec);
        }

        void fill(int x1, int y1, int z1, int x2, int y2, int z2, String spec) {
            fillCalls++;
            long volume = (long) (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1);
            assertTrue(volume <= 1_000_000L, "fill exceeds mod cap: " + volume);
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                        { fills++; set(x, y, z, spec); }
        }
    }

    private static Globals makeGlobals(FakeWorld world, boolean hasPlayer) {
        Globals g = LuaRuntime.createSandbox();
        LuaTable w = new LuaTable();
        w.set("setblock", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                world.set(a.checkint(1), a.checkint(2), a.checkint(3), a.checkjstring(4));
                return TRUE;
            }
        });
        w.set("fill", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                world.fill(a.checkint(1), a.checkint(2), a.checkint(3),
                        a.checkint(4), a.checkint(5), a.checkint(6), a.checkjstring(7));
                return TRUE;
            }
        });
        w.set("hollow", w.get("fill"));
        w.set("getblock", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                return valueOf(world.get(a.checkint(1), a.checkint(2), a.checkint(3)));
            }
        });
        w.set("spawn", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) { return TRUE; }
        });
        w.set("time", w.get("spawn"));
        w.set("weather", w.get("spawn"));
        g.set("world", w);

        LuaTable p = new LuaTable();
        p.set("exists", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) { return valueOf(hasPlayer); }
        });
        p.set("pos", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                return varargsOf(new LuaValue[]{valueOf(0.5), valueOf(64.0), valueOf(0.5)});
            }
        });
        p.set("name", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) { return valueOf("Wolfy"); }
        });
        p.set("teleport", w.get("spawn"));
        p.set("give", w.get("spawn"));
        g.set("player", p);

        VarArgFunction chat = new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                world.chat.add(a.arg(1).tojstring());
                return NIL;
            }
        };
        g.set("chat", chat);
        g.set("log", chat);
        g.set("print", chat);
        return g;
    }

    private static String runScript(Globals g) throws Exception {
        String code;
        try (InputStream in = ParkourScriptTest.class.getClassLoader()
                .getResourceAsStream("luamaps/parkour.lua")) {
            assertNotNull(in, "parkour.lua missing from resources");
            code = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return g.load(code, "parkour").call().tojstring();
    }

    /** Find the lodestone tip marker: scan +Z at y=200 in a small x window. */
    private static int findTipZ(FakeWorld w) {
        for (int z = -8; z < 20000; z++) {
            for (int dx = -4; dx <= 4; dx++) {
                if (MARKER.equals(w.get(dx, MARK_Y, z))) return z;
            }
        }
        return -1;
    }

    private static int odometer(FakeWorld w, int tipZ) {
        for (int y = INDEX_BASE; y < 320; y++) {
            // marker column shares the tip's x — find marker x first
            for (int dx = -4; dx <= 4; dx++) {
                if (MARKER.equals(w.get(dx, MARK_Y, tipZ))
                        && ODOMETER.equals(w.get(dx, y, tipZ))) {
                    return y - INDEX_BASE;
                }
            }
        }
        return -1;
    }

    @Test
    void freshRunGeneratesCourseAndMarker() throws Exception {
        FakeWorld w = new FakeWorld();
        Globals g = makeGlobals(w, true);
        String ret = runScript(g);
        assertTrue(w.setCalls > 100, "expected substantial generation, got " + w.setCalls);
        int tip = findTipZ(w);
        assertTrue(tip > 10, "tip marker not found");
        assertEquals(30, odometer(w, tip));
        assertTrue(w.chat.stream().anyMatch(s -> s.contains("segments 0-29")));
        assertTrue(ret.contains("segments=30"));
    }

    @Test
    void deterministicGeneration() throws Exception {
        FakeWorld a = new FakeWorld(), b = new FakeWorld();
        runScript(makeGlobals(a, true));
        runScript(makeGlobals(b, true));
        assertEquals(a.blocks, b.blocks);
    }

    @Test
    void secondRunExtendsFromMarker() throws Exception {
        FakeWorld w = new FakeWorld();
        runScript(makeGlobals(w, true));
        int tip1 = findTipZ(w);
        runScript(makeGlobals(w, true));
        int tip2 = findTipZ(w);
        assertTrue(tip2 > tip1, "tip should advance: " + tip1 + " -> " + tip2);
        assertEquals(60, odometer(w, tip2));
        assertTrue(w.chat.stream().anyMatch(s -> s.contains("segments 30-59")));
        // old marker cleared
        for (int dx = -4; dx <= 4; dx++) {
            assertFalse(MARKER.equals(w.get(dx, MARK_Y, tip1)), "stale marker left behind");
        }
    }

    @Test
    void worksWithoutPlayer() throws Exception {
        FakeWorld w = new FakeWorld();
        runScript(makeGlobals(w, false));
        assertTrue(findTipZ(w) > 0);
    }

    @Test
    void respectsWorldBoundsAndApiSurface() throws Exception {
        FakeWorld w = new FakeWorld();
        runScript(makeGlobals(w, true));
        for (Map.Entry<String, String> e : w.blocks.entrySet()) {
            String[] parts = e.getKey().split(",");
            int y = Integer.parseInt(parts[1]);
            assertTrue(y >= -64 && y <= 319, "block out of bounds: " + e.getKey());
        }
        assertTrue(w.chat.stream().anyMatch(s -> s.contains("[parkour]")));
    }
}
