package org.communitypoke.luamap.lua.api;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.communitypoke.luamap.lua.BlockStates;
import org.communitypoke.luamap.lua.LuaContext;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Optional;

/**
 * The {@code player} table exposed to Lua scripts. Every function raises a
 * Lua error when the script wasn't invoked by a player (e.g. from the server
 * console) — check {@code player.exists()} first if a script should run both
 * ways.
 *
 * <pre>
 *   player.exists()                 -> boolean
 *   player.name()                   -> "Wolfy"
 *   player.pos()                    -> x, y, z
 *   player.teleport(x, y, z)        -> true
 *   player.give("item", [count])    -> true
 * </pre>
 */
final class PlayerApi extends LuaTable {

    PlayerApi(LuaContext ctx) {
        set("exists", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return valueOf(ctx.playerOrNull() != null);
            }
        });

        set("name", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return valueOf(requirePlayer(ctx).getGameProfile().getName());
            }
        });

        set("pos", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ServerPlayerEntity p = requirePlayer(ctx);
                return varargsOf(new LuaValue[]{
                        valueOf(p.getX()), valueOf(p.getY()), valueOf(p.getZ())});
            }
        });

        set("teleport", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ServerPlayerEntity p = requirePlayer(ctx);
                p.teleport(ctx.world(),
                        args.checkdouble(1), args.checkdouble(2), args.checkdouble(3),
                        p.getYaw(), p.getPitch());
                return TRUE;
            }
        });

        set("give", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                ServerPlayerEntity p = requirePlayer(ctx);
                String spec = args.checkjstring(1);
                int count = args.optint(2, 1);
                Identifier id = new Identifier(BlockStates.normalizeId(spec));
                Optional<Item> item = Registries.ITEM.getOrEmpty(id);
                if (item.isEmpty()) {
                    throw new LuaError("Unknown item '" + id + "'");
                }
                ItemStack stack = new ItemStack(item.get(), count);
                p.getInventory().offerOrDrop(stack);
                p.playerScreenHandler.sendContentUpdates();
                return TRUE;
            }
        });
    }

    private static ServerPlayerEntity requirePlayer(LuaContext ctx) {
        ServerPlayerEntity p = ctx.playerOrNull();
        if (p == null) {
            throw new LuaError("player: no invoking player (run as a player, not the console)");
        }
        return p;
    }
}
