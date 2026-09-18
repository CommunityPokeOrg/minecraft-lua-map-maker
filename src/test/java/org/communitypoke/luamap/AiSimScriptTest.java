package org.communitypoke.luamap;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless harness for luamaps/ai_server_sim.lua: runs the real script against
 * {@link LuaTestEnv.FakeWorld} + {@link LuaTestEnv.FakeNpcs} and asserts NPC
 * creation, movement, chat, tasks, interactions, and resume behaviour.
 */
class AiSimScriptTest {

    private static final String MARKER = "minecraft:lodestone";
    private static final int MARK_Y = 250;

    private static Globals env(LuaTestEnv.FakeWorld w, LuaTestEnv.FakeNpcs n) {
        return LuaTestEnv.makeGlobals(w, n, true);
    }

    private static Map<String, double[]> snapshot(LuaTestEnv.FakeNpcs n) {
        Map<String, double[]> copy = new LinkedHashMap<>();
        n.pos.forEach((k, v) -> copy.put(k, v.clone()));
        return copy;
    }

    @Test
    void spawnsRosterAndRunsSteps() throws Exception {
        LuaTestEnv.FakeWorld w = new LuaTestEnv.FakeWorld();
        LuaTestEnv.FakeNpcs n = new LuaTestEnv.FakeNpcs();
        String ret = LuaTestEnv.run(env(w, n), "ai_server_sim");
        assertEquals(8, n.pos.size(), "full roster should spawn");
        assertTrue(ret.contains("steps=40"), ret);
        assertTrue(w.chat.stream().anyMatch(s -> s.contains("[sim]")));
        // anchor marker placed at the sim origin
        assertTrue(w.blocks.values().stream().anyMatch(MARKER::equals));
    }

    @Test
    void npcsWanderAndStayGrounded() throws Exception {
        LuaTestEnv.FakeWorld w = new LuaTestEnv.FakeWorld();
        LuaTestEnv.FakeNpcs n = new LuaTestEnv.FakeNpcs();
        LuaTestEnv.run(env(w, n), "ai_server_sim");
        Map<String, double[]> first = snapshot(n);
        LuaTestEnv.run(env(w, n), "ai_server_sim");
        boolean moved = false;
        for (Map.Entry<String, double[]> e : n.pos.entrySet()) {
            double[] a = first.get(e.getKey());
            double[] b = e.getValue();
            if (a[0] != b[0] || a[1] != b[1] || a[2] != b[2]) moved = true;
            // grounded: block under feet is solid
            assertFalse("minecraft:air".equals(
                    w.get((int) Math.floor(b[0]), (int) Math.floor(b[1]) - 1, (int) Math.floor(b[2]))),
                    e.getKey() + " floating");
        }
        assertTrue(moved, "npcs should wander between runs");
    }

    @Test
    void npcsChatPubliclyAndInteract() throws Exception {
        LuaTestEnv.FakeWorld w = new LuaTestEnv.FakeWorld();
        LuaTestEnv.FakeNpcs n = new LuaTestEnv.FakeNpcs();
        LuaTestEnv.run(env(w, n), "ai_server_sim");
        assertFalse(n.says.isEmpty(), "npcs should chatter");
        assertTrue(n.says.stream().allMatch(s -> s.matches("<Sim_[A-Za-z]+> .*")),
                "says formatted as <name> msg");
        // greeter/trader greet the nearby player deterministically on step 1
        assertTrue(n.says.stream().anyMatch(s -> s.contains("hey Wolfy!")),
                "expected a greeting for the player");
    }

    @Test
    void rolesPerformBlockTasks() throws Exception {
        LuaTestEnv.FakeWorld w = new LuaTestEnv.FakeWorld();
        LuaTestEnv.FakeNpcs n = new LuaTestEnv.FakeNpcs();
        LuaTestEnv.run(env(w, n), "ai_server_sim");
        assertTrue(w.blocks.containsValue("minecraft:oak_planks"), "builder should build");
        assertTrue(w.blocks.containsValue("minecraft:farmland"), "farmer should till");
        // miner digs: air written below ground level
        assertTrue(w.blocks.entrySet().stream().anyMatch(e ->
                        "minecraft:air".equals(e.getValue())
                                && Integer.parseInt(e.getKey().split(",")[1]) < 63),
                "miner should dig");
    }

    @Test
    void resumeAdvancesWithoutRespawning() throws Exception {
        LuaTestEnv.FakeWorld w = new LuaTestEnv.FakeWorld();
        LuaTestEnv.FakeNpcs n = new LuaTestEnv.FakeNpcs();
        LuaTestEnv.run(env(w, n), "ai_server_sim");
        String ret2 = LuaTestEnv.run(env(w, n), "ai_server_sim");
        assertEquals(8, n.pos.size(), "resume must not double-spawn");
        assertTrue(ret2.contains("steps=80"), ret2);
        assertTrue(w.chat.stream().anyMatch(s -> s.contains("resuming at step 40")));
    }

    @Test
    void deterministicAcrossRuns() throws Exception {
        LuaTestEnv.FakeWorld w1 = new LuaTestEnv.FakeWorld();
        LuaTestEnv.FakeNpcs n1 = new LuaTestEnv.FakeNpcs();
        LuaTestEnv.run(env(w1, n1), "ai_server_sim");
        LuaTestEnv.run(env(w1, n1), "ai_server_sim");

        LuaTestEnv.FakeWorld w2 = new LuaTestEnv.FakeWorld();
        LuaTestEnv.FakeNpcs n2 = new LuaTestEnv.FakeNpcs();
        LuaTestEnv.run(env(w2, n2), "ai_server_sim");
        LuaTestEnv.run(env(w2, n2), "ai_server_sim");

        assertEquals(w1.blocks, w2.blocks);
        assertEquals(n1.pos.keySet(), n2.pos.keySet());
        for (String name : n1.pos.keySet()) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(n1.pos.get(name), n2.pos.get(name),
                    "position mismatch for " + name);
        }
        assertEquals(n1.says, n2.says);
    }

    @Test
    void configOverrideShortensRun() throws Exception {
        LuaTestEnv.FakeWorld w = new LuaTestEnv.FakeWorld();
        LuaTestEnv.FakeNpcs n = new LuaTestEnv.FakeNpcs();
        Globals g = env(w, n);
        LuaTable cfg = new LuaTable();
        cfg.set("steps", 5);
        g.set("SIMCFG", cfg);
        String ret = LuaTestEnv.run(g, "ai_server_sim");
        assertTrue(ret.contains("steps=5"), ret);
    }
}
