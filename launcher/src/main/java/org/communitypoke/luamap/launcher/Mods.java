package org.communitypoke.luamap.launcher;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Populates {@code <gameDir>/mods} with the bundled mod jar + Fabric API. */
final class Mods {

    private Mods() {
    }

    static void install(Path gameDir, Main.Versions v) throws Exception {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        extractBundled(mods);
        downloadFabricApi(mods, v.fabricApi());
    }

    /** Copies every jar bundled under {@code bundled-mods/} in this jar into mods/. */
    private static void extractBundled(Path mods) throws Exception {
        java.io.File self;
        try {
            self = new java.io.File(
                    Mods.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            return;
        }
        if (!self.isFile() || !self.getName().endsWith(".jar")) {
            return; // running from an IDE/classes dir — nothing bundled
        }
        try (JarFile jar = new JarFile(self)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.startsWith("bundled-mods/") || !name.endsWith(".jar")) {
                    continue;
                }
                Path out = mods.resolve(name.substring("bundled-mods/".length()));
                if (Files.exists(out)) {
                    continue;
                }
                try (InputStream in = jar.getInputStream(e)) {
                    Files.copy(in, out);
                }
                System.out.println("Installed mod jar: " + out.getFileName());
            }
        }
    }

    private static void downloadFabricApi(Path mods, String version) throws Exception {
        String file = "fabric-api-" + version + ".jar";
        Path out = mods.resolve(file);
        if (Files.exists(out)) {
            return;
        }
        String url = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/"
                + version + "/" + file;
        System.out.println("Downloading Fabric API " + version);
        Http.download(url, out);
    }
}
