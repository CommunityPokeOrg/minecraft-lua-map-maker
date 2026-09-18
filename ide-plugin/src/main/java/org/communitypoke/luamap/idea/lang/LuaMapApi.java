package org.communitypoke.luamap.idea.lang;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The LuaMap/NPC Lua API surface — shared by highlighting and completion. */
public final class LuaMapApi {

    /** root -> member calls, kept in sync with README "Lua API". */
    public static final Map<String, List<String>> MEMBERS = Map.of(
            "world", List.of(
                    "setblock", "fill", "hollow", "getblock", "spawn", "time", "weather"),
            "player", List.of(
                    "exists", "name", "pos", "teleport", "give"),
            "npc", List.of(
                    "spawn", "exists", "remove", "removeAll", "list", "count",
                    "pos", "moveto", "look", "say"));

    public static final Set<String> ROOTS = MEMBERS.keySet();

    public static final Set<String> GLOBALS = Set.of("chat", "log", "print");

    /** Suggested call-site snippets for completion. */
    public static String lookupTail(String root, String member) {
        return switch (root + "." + member) {
            case "world.setblock" -> "(x, y, z, \"minecraft:stone\")";
            case "world.fill", "world.hollow" -> "(x1, y1, z1, x2, y2, z2, \"minecraft:stone\")";
            case "world.getblock" -> "(x, y, z)";
            case "world.spawn" -> "(x, y, z)";
            case "world.time" -> "(\"day\")";
            case "world.weather" -> "(\"clear\")";
            case "player.pos" -> "()";
            case "player.teleport" -> "(x, y, z)";
            case "player.give" -> "(\"minecraft:item\", 1)";
            case "npc.spawn" -> "(\"Sim_Name\", x, y, z)";
            case "npc.pos", "npc.remove", "npc.exists" -> "(\"name\")";
            case "npc.moveto" -> "(\"name\", x, y, z)";
            case "npc.look" -> "(\"name\", yaw, pitch)";
            case "npc.say" -> "(\"name\", \"message\")";
            default -> "()";
        };
    }

    private LuaMapApi() {
    }
}
