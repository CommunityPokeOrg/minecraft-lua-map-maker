package org.communitypoke.luamap.launcher;

import java.nio.file.Path;
import java.util.Properties;

/**
 * Single-file launcher for Lua Map Maker.
 *
 * <pre>
 *   java -jar luamap-launcher.jar [options]
 *
 *   --server          launch a dedicated server instead of the client
 *                     (handy on headless machines; /luamap works from the console)
 *   --gameDir DIR     run directory (default: ./luamap-run)
 *   --username NAME   offline-mode username (default: Wolfy)
 *   --xmx SIZE        heap for the game process (default: 2G)
 *   --mc VERSION      override Minecraft version
 * </pre>
 *
 * The launcher downloads the vanilla game, libraries, assets, and the Fabric
 * loader profile on first run, extracts the bundled mod jar into
 * {@code <gameDir>/mods}, then starts the game. Everything lands under the
 * game directory — no Minecraft installation required.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        boolean server = false;
        Path gameDir = Path.of("luamap-run");
        String username = "Wolfy";
        String xmx = "2G";
        String mcOverride = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server" -> server = true;
                case "--gameDir" -> gameDir = Path.of(args[++i]);
                case "--username" -> username = args[++i];
                case "--xmx" -> xmx = args[++i];
                case "--mc" -> mcOverride = args[++i];
                case "--help", "-h" -> {
                    System.out.println(usage());
                    return;
                }
                default -> {
                    System.err.println("Unknown option: " + args[i] + "\n\n" + usage());
                    System.exit(2);
                }
            }
        }

        Properties p = new Properties();
        try (var in = Main.class.getResourceAsStream("/launcher.properties")) {
            if (in == null) {
                System.err.println("launcher.properties missing — this jar was not built via Gradle");
                System.exit(1);
            }
            p.load(in);
        }
        Versions v = new Versions(
                mcOverride != null ? mcOverride : p.getProperty("mc_version"),
                p.getProperty("loader_version"),
                p.getProperty("fabric_api_version"),
                p.getProperty("installer_version"),
                p.getProperty("mod_version"));

        System.out.println("Lua Map Maker launcher — Minecraft " + v.mc()
                + " / Fabric Loader " + v.loader() + " / mod " + v.mod());
        System.out.println("Game dir: " + gameDir.toAbsolutePath());

        int code = server
                ? ServerFlow.run(v, gameDir, xmx)
                : ClientFlow.run(v, gameDir, username, xmx);
        System.exit(code);
    }

    private static String usage() {
        return "java -jar luamap-launcher.jar [--server] [--gameDir DIR] [--username NAME] [--xmx SIZE] [--mc VERSION]";
    }

    record Versions(String mc, String loader, String fabricApi, String installer, String mod) {
    }
}
