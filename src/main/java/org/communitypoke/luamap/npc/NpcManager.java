package org.communitypoke.luamap.npc;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Registry of simulated players ("NPCs") backed by Fabric {@link FakePlayer}
 * entities — real ServerPlayerEntity instances that appear in the tab list and
 * can be moved/ticked. The registry is static so NPCs persist across separate
 * script runs (scripts are synchronous and stateless; the entities and this
 * map live as long as the server does).
 */
public final class NpcManager {

    /** Minecraft profile names: 3-16 chars, letters/digits/underscore. */
    private static final Pattern NAME_RULE = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private static final Map<String, FakePlayer> NPCS = new LinkedHashMap<>();

    private NpcManager() {
    }

    /** Prune entries whose entity was killed/despawned since the last call. */
    private static void prune() {
        NPCS.values().removeIf(e -> e.isRemoved() || !e.isAlive());
    }

    public static boolean exists(ServerWorld world, String name) {
        prune();
        FakePlayer p = NPCS.get(name);
        return p != null && p.getWorld() == world;
    }

    /**
     * Spawn a named fake player at the given position.
     *
     * @throws IllegalArgumentException bad name or name already in use
     */
    public static FakePlayer spawn(ServerWorld world, String name, double x, double y, double z) {
        if (!NAME_RULE.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "invalid npc name '" + name + "' (3-16 chars, [A-Za-z0-9_])");
        }
        prune();
        if (NPCS.containsKey(name)) {
            throw new IllegalArgumentException("npc '" + name + "' already exists");
        }
        // Deterministic UUID per name so a respawned NPC keeps its identity.
        UUID uuid = UUID.nameUUIDFromBytes(("luamap-npc:" + name).getBytes(StandardCharsets.UTF_8));
        FakePlayer p = FakePlayer.get(world, new GameProfile(uuid, name));
        p.teleport(world, x, y, z, 0f, 0f);
        NPCS.put(name, p);
        return p;
    }

    public static boolean remove(String name) {
        prune();
        FakePlayer p = NPCS.remove(name);
        if (p == null) {
            return false;
        }
        try {
            p.getServer().getPlayerManager().remove(p);
        } catch (Throwable ignored) {
        }
        try {
            p.discard();
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static void removeAll() {
        for (String name : NPCS.keySet().toArray(new String[0])) {
            remove(name);
        }
    }

    /** Live entity for a name, or null. */
    public static ServerPlayerEntity get(String name) {
        prune();
        return NPCS.get(name);
    }

    /** Snapshot of current NPC names in spawn order. */
    public static Map<String, FakePlayer> all() {
        prune();
        return Map.copyOf(NPCS);
    }

    /** Broadcast a chat line as the NPC ({@code <name> message}). */
    public static void say(ServerPlayerEntity p, String message) {
        p.getServer().getPlayerManager()
                .broadcast(Text.literal("<" + p.getGameProfile().getName() + "> " + message), false);
    }
}
