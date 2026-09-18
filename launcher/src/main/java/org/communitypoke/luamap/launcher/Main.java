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
 *   --gameDir DIR     run directory (default: &lt;launcher dir&gt;/luamap-run)
 *   --username NAME   offline-mode username (default: Wolfy)
 *   --xmx SIZE        heap for the game process (default: 2G)
 *   --mc VERSION      override Minecraft version
 *   --bridgePort N    enable LuaBridge on 127.0.0.1:N (IDE live-eval/debug;
 *                     the LuaMap IntelliJ plugin connects here)
 *   --setup-ide       provision a managed IntelliJ IDEA CE under
 *                     .luamap/ide/ with the LuaMap plugin installed, print the
 *                     path, and exit (no game launch)
 *   --ide             same provisioning, then launch the IDE and exit
 *   --idePath DIR     use an existing IntelliJ install instead of the managed
 *                     one (the plugin is still installed into it)
 * </pre>
 *
 * The launcher is anchored to the directory containing its own jar — the
 * managed {@code javahome/} Java 17 runtime and the default {@code
 * luamap-run/} game dir live there, so behavior doesn't depend on where the
 * process was started from.
 *
 * <p>On start it ensures {@code <launcher dir>/javahome} holds a Java 17
 * runtime — auto-downloading a Temurin JRE 17 for the current OS/arch if not —
 * and always launches Minecraft with that Java, never a newer system JDK
 * (which Fabric/ASM reject, e.g. Java 26 / class version 70).
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        boolean server = false;
        Path gameDir = null;
        String username = "Wolfy";
        String xmx = "2G";
        String mcOverride = null;
        int bridgePort = 0;
        IdeFlags.Result ide = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server" -> server = true;
                case "--gameDir" -> gameDir = Path.of(args[++i]);
                case "--username" -> username = args[++i];
                case "--xmx" -> xmx = args[++i];
                case "--mc" -> mcOverride = args[++i];
                case "--bridgePort" -> bridgePort = Integer.parseInt(args[++i]);
                case "--setup-ide", "--ide", "--idePath" -> {
                    if (ide == null) {
                        ide = IdeFlags.parse(args);
                    }
                    if (args[i].equals("--idePath")) {
                        i++; // consume the value
                    }
                }
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

        Path launcherDir = LauncherDirs.launcherDir();
        if (gameDir == null) {
            gameDir = launcherDir.resolve("luamap-run");
        } else if (!gameDir.isAbsolute()) {
            gameDir = launcherDir.resolve(gameDir);
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
        System.out.println("Launcher dir: " + launcherDir);
        System.out.println("Game dir: " + gameDir.toAbsolutePath());

        if (ide != null && ide.mode() != IdeFlags.Mode.NONE) {
            Path ideDir = IdeProvisioner.ensureIde(launcherDir, ide.idePath());
            System.out.println("IDE ready at: " + ideDir);
            if (ide.mode() == IdeFlags.Mode.LAUNCH) {
                IdeProvisioner.launch(ideDir);
            }
            return; // IDE modes never start the game
        }

        // Managed Java 17 — downloaded into javahome/ on first run.
        Path java = JavaProvisioner.ensureJava17(launcherDir);

        if (bridgePort > 0) {
            System.out.println("LuaBridge enabled on 127.0.0.1:" + bridgePort
                    + " (IDE plugin endpoint; -Dluamap.bridge.port passed to the game)");
        }

        int code = server
                ? ServerFlow.run(v, gameDir, java, xmx, bridgePort)
                : ClientFlow.run(v, gameDir, java, username, xmx, bridgePort);
        System.exit(code);
    }

    private static String usage() {
        return "java -jar luamap-launcher.jar [--server] [--gameDir DIR] [--username NAME] [--xmx SIZE] [--mc VERSION] [--bridgePort N] [--setup-ide|--ide [--idePath DIR]]";
    }

    record Versions(String mc, String loader, String fabricApi, String installer, String mod) {
    }
}
