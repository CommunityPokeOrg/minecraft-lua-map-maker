package org.communitypoke.luamap.lua;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.InvalidIdentifierException;

import java.util.Optional;

/**
 * Parses Lua-side block specifiers like {@code "stone"},
 * {@code "minecraft:chest[facing=north,waterlogged=false]"} into
 * {@link BlockState}s. A missing namespace defaults to {@code minecraft}.
 */
public final class BlockStates {

    private BlockStates() {
    }

    /** Pure-namespace helper: "stone" -> "minecraft:stone". */
    public static String normalizeId(String spec) {
        String id = spec;
        int bracket = id.indexOf('[');
        if (bracket >= 0) {
            id = id.substring(0, bracket);
        }
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        return id;
    }

    /**
     * @throws IllegalArgumentException if the block or a property is unknown
     */
    public static BlockState parse(String spec) {
        String id = normalizeId(spec);
        Identifier ident;
        try {
            ident = new Identifier(id);
        } catch (InvalidIdentifierException e) {
            throw new IllegalArgumentException("Invalid block id '" + id + "'");
        }
        Optional<Block> block = Registries.BLOCK.getOrEmpty(ident);
        if (block.isEmpty()) {
            throw new IllegalArgumentException("Unknown block '" + id + "'");
        }
        BlockState state = block.get().getDefaultState();

        int bracket = spec.indexOf('[');
        if (bracket >= 0) {
            int end = spec.indexOf(']', bracket);
            if (end < 0) {
                throw new IllegalArgumentException("Unclosed '[' in '" + spec + "'");
            }
            String props = spec.substring(bracket + 1, end);
            for (String pair : props.split(",")) {
                String[] kv = pair.trim().split("=", 2);
                if (kv.length != 2) {
                    throw new IllegalArgumentException("Bad property '" + pair + "' in '" + spec + "'");
                }
                state = applyProperty(state, kv[0].trim(), kv[1].trim(), spec);
            }
        }
        return state;
    }

    private static BlockState applyProperty(BlockState state, String key, String value, String spec) {
        Property<?> prop = state.getBlock().getStateManager().getProperty(key);
        if (prop == null) {
            throw new IllegalArgumentException("Unknown property '" + key + "' in '" + spec + "'");
        }
        return withUnchecked(state, prop, value, spec);
    }

    private static <T extends Comparable<T>> BlockState withUnchecked(
            BlockState state, Property<T> prop, String value, String spec) {
        Optional<T> parsed = prop.parse(value);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(
                    "Bad value '" + value + "' for property '" + prop.getName() + "' in '" + spec + "'");
        }
        return state.with(prop, parsed.get());
    }
}
