package org.communitypoke.luamap;

import org.communitypoke.luamap.lua.LuaRuntime;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared headless harness for bundled-script tests: injects fake
 * {@code world}/{@code player}/{@code npc} backends into the real sandboxed
 * globals, then runs an actual script file out of resources.
 */
final class LuaTestEnv {

    /** Block simulation: flat dirt below y=64, air above, edits tracked. */
    static final class FakeWorld {
        final Map<String, String> blocks = new LinkedHashMap<>();
        final List<String> chat = new ArrayList<>();

        private static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        String get(int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), y <= 63 ? "minecraft:dirt" : "minecraft:air");
        }

        void set(int x, int y, int z, String spec) {
            blocks.put(key(x, y, z), spec);
        }

        void fill(int x1, int y1, int z1, int x2, int y2, int z2, String spec) {
            long volume = (long) (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1);
            assertTrue(volume <= 1_000_000L, "fill exceeds mod cap: " + volume);
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                        set(x, y, z, spec);
        }
    }

    /** NPC simulation: name -> position, plus a broadcast log for npc.say. */
    static final class FakeNpcs {
        final Map<String, double[]> pos = new LinkedHashMap<>();
        final List<String> says = new ArrayList<>();
        final Map<String, float[]> looks = new LinkedHashMap<>();

        void spawn(String name, double x, double y, double z) {
            if (!name.matches("[A-Za-z0-9_]{3,16}")) {
                throw new LuaError("invalid npc name '" + name + "'");
            }
            if (pos.containsKey(name)) {
                throw new LuaError("npc '" + name + "' already exists");
            }
            pos.put(name, new double[]{x, y, z});
        }

        double[] need(String name) {
            double[] p = pos.get(name);
            if (p == null) {
                throw new LuaError("npc: unknown npc '" + name + "'");
            }
            return p;
        }
    }

    static Globals makeGlobals(FakeWorld w, boolean hasPlayer) {
        return makeGlobals(w, null, hasPlayer);
    }

    static Globals makeGlobals(FakeWorld w, FakeNpcs npcs, boolean hasPlayer) {
        Globals g = LuaRuntime.createSandbox();

        LuaTable world = new LuaTable();
        world.set("setblock", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                w.set(a.checkint(1), a.checkint(2), a.checkint(3), a.checkjstring(4));
                return TRUE;
            }
        });
        world.set("fill", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                w.fill(a.checkint(1), a.checkint(2), a.checkint(3),
                        a.checkint(4), a.checkint(5), a.checkint(6), a.checkjstring(7));
                return TRUE;
            }
        });
        world.set("hollow", world.get("fill"));
        world.set("getblock", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                return valueOf(w.get(a.checkint(1), a.checkint(2), a.checkint(3)));
            }
        });
        VarArgFunction noop = new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) { return TRUE; }
        };
        world.set("spawn", noop);
        world.set("time", noop);
        world.set("weather", noop);
        g.set("world", world);

        LuaTable player = new LuaTable();
        player.set("exists", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) { return valueOf(hasPlayer); }
        });
        player.set("pos", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                return varargsOf(new LuaValue[]{valueOf(0.5), valueOf(64.0), valueOf(0.5)});
            }
        });
        player.set("name", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) { return valueOf("Wolfy"); }
        });
        player.set("teleport", noop);
        player.set("give", noop);
        g.set("player", player);

        if (npcs != null) {
            LuaTable n = new LuaTable();
            n.set("spawn", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    npcs.spawn(a.checkjstring(1), a.checkdouble(2), a.checkdouble(3), a.checkdouble(4));
                    return TRUE;
                }
            });
            n.set("exists", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    return valueOf(npcs.pos.containsKey(a.checkjstring(1)));
                }
            });
            n.set("remove", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    return valueOf(npcs.pos.remove(a.checkjstring(1)) != null);
                }
            });
            n.set("removeAll", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    int c = npcs.pos.size();
                    npcs.pos.clear();
                    return valueOf(c);
                }
            });
            n.set("list", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    LuaTable t = new LuaTable();
                    for (Map.Entry<String, double[]> e : npcs.pos.entrySet()) {
                        LuaTable p = new LuaTable();
                        p.set("x", valueOf(e.getValue()[0]));
                        p.set("y", valueOf(e.getValue()[1]));
                        p.set("z", valueOf(e.getValue()[2]));
                        t.set(e.getKey(), p);
                    }
                    return t;
                }
            });
            n.set("count", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) { return valueOf(npcs.pos.size()); }
            });
            n.set("pos", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    double[] p = npcs.need(a.checkjstring(1));
                    return varargsOf(new LuaValue[]{valueOf(p[0]), valueOf(p[1]), valueOf(p[2])});
                }
            });
            n.set("moveto", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    double[] p = npcs.need(a.checkjstring(1));
                    p[0] = a.checkdouble(2);
                    p[1] = a.checkdouble(3);
                    p[2] = a.checkdouble(4);
                    return TRUE;
                }
            });
            n.set("look", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    npcs.looks.put(a.checkjstring(1),
                            new float[]{(float) a.checkdouble(2), (float) a.checkdouble(3)});
                    return TRUE;
                }
            });
            n.set("say", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    npcs.need(a.checkjstring(1));
                    npcs.says.add("<" + a.checkjstring(1) + "> " + a.checkjstring(2));
                    return TRUE;
                }
            });
            g.set("npc", n);
        }

        VarArgFunction chat = new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                w.chat.add(a.arg(1).tojstring());
                return NIL;
            }
        };
        g.set("chat", chat);
        g.set("log", chat);
        g.set("print", chat);
        return g;
    }

    /** Load and run a bundled script ("parkour" -> luamaps/parkour.lua). */
    static String run(Globals g, String script) throws Exception {
        String code;
        try (InputStream in = LuaTestEnv.class.getClassLoader()
                .getResourceAsStream("luamaps/" + script + ".lua")) {
            assertNotNull(in, script + ".lua missing from resources");
            code = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return g.load(code, script).call().tojstring();
    }

    private LuaTestEnv() {
    }
}
