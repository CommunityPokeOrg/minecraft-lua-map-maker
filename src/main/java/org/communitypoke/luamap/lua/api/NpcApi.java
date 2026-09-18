package org.communitypoke.luamap.lua.api;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.communitypoke.luamap.lua.LuaContext;
import org.communitypoke.luamap.npc.NpcManager;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Map;

/**
 * The {@code npc} table exposed to Lua scripts: simulated players backed by
 * Fabric fake-player entities. NPCs persist across runs of a script — use
 * {@code npc.list()} on re-entry to resume control of an existing population.
 *
 * <pre>
 *   npc.spawn(name, x, y, z)      -> true       (name: 3-16 [A-Za-z0-9_], must be unique)
 *   npc.exists(name)              -> boolean
 *   npc.remove(name)              -> boolean
 *   npc.removeAll()               -> count removed
 *   npc.list()                    -> {name={x=..,y=..,z=..}, ...}
 *   npc.count()                   -> n
 *   npc.pos(name)                 -> x, y, z
 *   npc.moveto(name, x, y, z)     -> true       (teleport-step movement)
 *   npc.look(name, yaw, pitch)    -> true
 *   npc.say(name, "text")         -> true       (broadcasts "<name> text")
 * </pre>
 */
final class NpcApi extends LuaTable {

    NpcApi(LuaContext ctx) {
        ServerWorld world = ctx.world();

        set("spawn", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String name = args.checkjstring(1);
                double x = args.checkdouble(2);
                double y = args.checkdouble(3);
                double z = args.checkdouble(4);
                try {
                    NpcManager.spawn(world, name, x, y, z);
                } catch (IllegalArgumentException e) {
                    throw new LuaError(e.getMessage());
                }
                return TRUE;
            }
        });

        set("exists", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return valueOf(NpcManager.exists(world, args.checkjstring(1)));
            }
        });

        set("remove", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return valueOf(NpcManager.remove(args.checkjstring(1)));
            }
        });

        set("removeAll", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int n = NpcManager.all().size();
                NpcManager.removeAll();
                return valueOf(n);
            }
        });

        set("list", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaTable t = new LuaTable();
                for (Map.Entry<String, FakePlayer> e : NpcManager.all().entrySet()) {
                    ServerPlayerEntity p = e.getValue();
                    LuaTable pos = new LuaTable();
                    pos.set("x", valueOf(p.getX()));
                    pos.set("y", valueOf(p.getY()));
                    pos.set("z", valueOf(p.getZ()));
                    t.set(e.getKey(), pos);
                }
                return t;
            }
        });

        set("count", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return valueOf(NpcManager.all().size());
            }
        });

        set("pos", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ServerPlayerEntity p = require(ctx, args.checkjstring(1));
                return varargsOf(new LuaValue[]{
                        valueOf(p.getX()), valueOf(p.getY()), valueOf(p.getZ())});
            }
        });

        set("moveto", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ServerPlayerEntity p = require(ctx, args.checkjstring(1));
                p.teleport(ctx.world(),
                        args.checkdouble(2), args.checkdouble(3), args.checkdouble(4),
                        p.getYaw(), p.getPitch());
                return TRUE;
            }
        });

        set("look", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ServerPlayerEntity p = require(ctx, args.checkjstring(1));
                p.teleport(ctx.world(), p.getX(), p.getY(), p.getZ(),
                        (float) args.checkdouble(2), (float) args.checkdouble(3));
                return TRUE;
            }
        });

        set("say", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ServerPlayerEntity p = require(ctx, args.checkjstring(1));
                NpcManager.say(p, args.checkjstring(2));
                return TRUE;
            }
        });
    }

    private static ServerPlayerEntity require(LuaContext ctx, String name) {
        ServerPlayerEntity p = NpcManager.get(name);
        if (p == null || p.getWorld() != ctx.world()) {
            throw new LuaError("npc: unknown npc '" + name + "'");
        }
        return p;
    }
}
