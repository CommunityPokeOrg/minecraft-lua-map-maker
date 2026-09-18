package org.communitypoke.luamap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.communitypoke.luamap.command.LuaMapCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Lua Map Maker — entry point.
 *
 * Registers the /luamap command and seeds the bundled example scripts into
 * {@code <gameDir>/luamaps/} on first run.
 */
public class LuaMapMakerMod implements ModInitializer {
    public static final String MOD_ID = "luamap";
    public static final Logger LOGGER = LoggerFactory.getLogger("LuaMapMaker");

    @Override
    public void onInitialize() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        ScriptLibrary scripts = new ScriptLibrary(gameDir.resolve("luamaps"));

        try {
            int seeded = scripts.seedBundledExamples();
            if (seeded > 0) {
                LOGGER.info("Seeded {} example Lua map script(s) into {}", seeded, scripts.directory());
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to seed example Lua scripts", e);
        }

        LuaMapCommand.register(scripts);
        LOGGER.info("Lua Map Maker loaded — {} script(s) in {}", scripts.list().size(), scripts.directory());
    }
}
