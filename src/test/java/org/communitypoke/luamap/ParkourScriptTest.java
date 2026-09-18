package org.communitypoke.luamap;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless harness for luamaps/parkour.lua: runs the actual script against
 * {@link LuaTestEnv.FakeWorld} and asserts generation behaviour.
 */
class ParkourScriptTest {

    private static final String MARKER = "minecraft:lodestone";
    private static final String ODOMETER = "minecraft:bedrock";
    private static final int MARK_Y = 200;
    private static final int INDEX_BASE = 201;

    /** Find the lodestone tip marker: scan +Z at y=200 in a small x window. */
    private static int findTipZ(LuaTestEnv.FakeWorld w) {
        for (int z = -8; z < 20000; z++) {
            for (int dx = -4; dx <= 4; dx++) {
                if (MARKER.equals(w.get(dx, MARK_Y, z))) return z;
            }
        }
        return -1;
    }

    private static int odometer(LuaTestEnv.FakeWorld w, int tipZ) {
        for (int y = INDEX_BASE; y < 320; y++) {
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
        LuaTestEnv.FakeWorld w = new LuaTestEnv.FakeWorld();
        String ret = LuaTestEnv.run(LuaTestEnv.makeGlobals(w, true), "parkour");
        assertTrue(w.blocks.size() > 100, "expected substantial generation");
        int tip = findTipZ(w);
        assertTrue(tip > 10, "tip marker not found");
        assertEquals(30, odometer(w, tip));
        assertTrue(w.chat.stream().anyMatch(s -> s.contains("segments 0-29")));
        assertTrue(ret.contains("segments=30"));
    }

    @Test
    void deterministicGeneration() throws Exception {
        LuaTestEnv.FakeWorld a = new LuaTestEnv.FakeWorld();
        LuaTestEnv.FakeWorld b = new LuaTestEnv.FakeWorld();
        LuaTestEnv.run(LuaTestEnv.makeGlobals(a, true), "parkour");
        LuaTestEnv.run(LuaTestEnv.makeGlobals(b, true), "parkour");
        assertEquals(a.blocks, b.blocks);
    }

    @Test
    void secondRunExtendsFromMarker() throws Exception {
        LuaTestEnv.FakeWorld w = new LuaTestEnv.FakeWorld();
        LuaTestEnv.run(LuaTestEnv.makeGlobals(w, true), "parkour");
        int tip1 = findTipZ(w);
        LuaTestEnv.run(LuaTestEnv.makeGlobals(w, true), "parkour");
        int tip2 = findTipZ(w);
        assertTrue(tip2 > tip1, "tip should advance: " + tip1 + " -> " + tip2);
        assertEquals(60, odometer(w, tip2));
        assertTrue(w.chat.stream().anyMatch(s -> s.contains("segments 30-59")));
        for (int dx = -4; dx <= 4; dx++) {
            assertFalse(MARKER.equals(w.get(dx, MARK_Y, tip1)), "stale marker left behind");
        }
    }

    @Test
    void worksWithoutPlayer() throws Exception {
        LuaTestEnv.FakeWorld w = new LuaTestEnv.FakeWorld();
        LuaTestEnv.run(LuaTestEnv.makeGlobals(w, false), "parkour");
        assertTrue(findTipZ(w) > 0);
    }

    @Test
    void respectsWorldBoundsAndApiSurface() throws Exception {
        LuaTestEnv.FakeWorld w = new LuaTestEnv.FakeWorld();
        LuaTestEnv.run(LuaTestEnv.makeGlobals(w, true), "parkour");
        for (Map.Entry<String, String> e : w.blocks.entrySet()) {
            String[] parts = e.getKey().split(",");
            int y = Integer.parseInt(parts[1]);
            assertTrue(y >= -64 && y <= 319, "block out of bounds: " + e.getKey());
        }
        assertTrue(w.chat.stream().anyMatch(s -> s.contains("[parkour]")));
    }
}
