package org.communitypoke.luamap.launcher;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server path: download the official Fabric server launcher jar, write a
 * friendly default server.properties (creative flat world, offline mode),
 * install mods, then run it. Lua commands work from the server console:
 * type {@code luamap run arena} at the prompt.
 */
final class ServerFlow {

    private ServerFlow() {
    }

    static int run(Main.Versions v, Path gameDir, String xmx) throws Exception {
        Path abs = gameDir.toAbsolutePath();
        Files.createDirectories(abs);

        String jarUrl = "https://meta.fabricmc.net/v2/versions/loader/" + v.mc() + "/"
                + v.loader() + "/" + v.installer() + "/server/jar";
        Path launcherJar = abs.resolve("fabric-server-launcher.jar");
        Http.download(jarUrl, launcherJar);

        Path eula = abs.resolve("eula.txt");
        if (!Files.exists(eula)) {
            Files.writeString(eula, "# accepted via luamap-launcher — see https://aka.ms/MinecraftEULA\neula=true\n");
        }
        Path props = abs.resolve("server.properties");
        if (!Files.exists(props)) {
            Files.writeString(props, String.join("\n",
                    "online-mode=false",
                    "level-type=minecraft\\:flat",
                    "gamemode=creative",
                    "difficulty=peaceful",
                    "spawn-protection=0",
                    "sync-chunk-writes=false",
                    "max-tick-time=-1",
                    ""));
        }

        Mods.install(abs, v);

        String exe = "windows".equals(Os.name()) ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", exe).toString();
        System.out.println("Starting server (flat creative world). Try: luamap run arena");
        ProcessBuilder pb = new ProcessBuilder(java, "-Xmx" + xmx, "-jar",
                launcherJar.getFileName().toString(), "nogui");
        pb.directory(abs.toFile());
        pb.inheritIO();
        return pb.start().waitFor();
    }
}
