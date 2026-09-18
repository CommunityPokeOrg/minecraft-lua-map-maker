package org.communitypoke.luamap.lua.api;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.communitypoke.luamap.lua.BlockStates;
import org.communitypoke.luamap.lua.LuaContext;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * The {@code world} table exposed to Lua scripts.
 *
 * <pre>
 *   world.setblock(x, y, z, "block[state]")         -> true
 *   world.fill(x1,y1,z1, x2,y2,z2, "block")         -> blocks placed
 *   world.hollow(x1,y1,z1, x2,y2,z2, "block")       -> blocks placed (shell only)
 *   world.getblock(x, y, z)                          -> "minecraft:stone"
 *   world.spawn(x, y, z)                             -> true (set world spawn)
 *   world.time("day"|"noon"|"night"|"midnight"|ticks) -> true
 *   world.weather("clear"|"rain"|"thunder")          -> true
 * </pre>
 */
final class WorldApi extends LuaTable {

    /** Safety cap on a single fill/hollow call. */
    static final long MAX_VOLUME = 1_000_000L;

    private static final int PROGRESS_STEP = 250_000;

    WorldApi(LuaContext ctx) {
        ServerWorld world = ctx.world();

        set("setblock", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                BlockPos pos = pos(args, 1);
                world.setBlockState(pos, block(args, 4));
                return TRUE;
            }
        });

        set("fill", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return valueOf(place(ctx, box(args), block(args, 7), false));
            }
        });

        set("hollow", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return valueOf(place(ctx, box(args), block(args, 7), true));
            }
        });

        set("getblock", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                BlockState state = world.getBlockState(pos(args, 1));
                Identifier id = Registries.BLOCK.getId(state.getBlock());
                return valueOf(id.toString());
            }
        });

        set("spawn", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                BlockPos pos = pos(args, 1);
                world.getServer().getOverworld().setSpawnPos(pos, 0.0f);
                return TRUE;
            }
        });

        set("time", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                world.setTimeOfDay(parseTime(args.arg(1)));
                return TRUE;
            }
        });

        set("weather", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String kind = args.checkjstring(1);
                switch (kind) {
                    case "clear" -> world.setWeather(1_200_000, 0, false, false);
                    case "rain" -> world.setWeather(0, 1_200_000, true, false);
                    case "thunder" -> world.setWeather(0, 1_200_000, true, true);
                    default -> throw new LuaError("weather: expected clear|rain|thunder, got '" + kind + "'");
                }
                return TRUE;
            }
        });
    }

    private static BlockPos pos(Varargs args, int i) {
        return new BlockPos(args.checkint(i), args.checkint(i + 1), args.checkint(i + 2));
    }

    private static BlockState block(Varargs args, int i) {
        try {
            return BlockStates.parse(args.checkjstring(i));
        } catch (IllegalArgumentException e) {
            throw new LuaError(e.getMessage());
        }
    }

    private static int[] box(Varargs args) {
        int x1 = args.checkint(1), y1 = args.checkint(2), z1 = args.checkint(3);
        int x2 = args.checkint(4), y2 = args.checkint(5), z2 = args.checkint(6);
        return new int[]{
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)};
    }

    private static long place(LuaContext ctx, int[] box, BlockState state, boolean shellOnly) {
        long volume = (long) (box[3] - box[0] + 1)
                * (box[4] - box[1] + 1)
                * (box[5] - box[2] + 1);
        if (volume > MAX_VOLUME) {
            throw new LuaError("fill volume " + volume + " exceeds limit " + MAX_VOLUME
                    + " — split it into smaller regions");
        }
        ServerWorld world = ctx.world();
        long placed = 0;
        for (int x = box[0]; x <= box[3]; x++) {
            for (int y = box[1]; y <= box[4]; y++) {
                for (int z = box[2]; z <= box[5]; z++) {
                    if (shellOnly
                            && x != box[0] && x != box[3]
                            && y != box[1] && y != box[4]
                            && z != box[2] && z != box[5]) {
                        continue;
                    }
                    world.setBlockState(new BlockPos(x, y, z), state);
                    placed++;
                }
            }
            if (placed >= PROGRESS_STEP && placed % PROGRESS_STEP == 0) {
                ctx.out("…placed " + placed + " blocks");
            }
        }
        return placed;
    }

    private static long parseTime(LuaValue arg) {
        if (arg.isnumber()) {
            return arg.checklong();
        }
        String s = arg.checkjstring();
        return switch (s) {
            case "day" -> 1000L;
            case "noon" -> 6000L;
            case "night" -> 13000L;
            case "midnight" -> 18000L;
            default -> {
                try {
                    yield Long.parseLong(s);
                } catch (NumberFormatException e) {
                    throw new LuaError("time: expected day|noon|night|midnight|ticks, got '" + s + "'");
                }
            }
        };
    }
}
