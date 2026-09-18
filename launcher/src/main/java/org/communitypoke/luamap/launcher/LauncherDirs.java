package org.communitypoke.luamap.launcher;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;

/**
 * Resolves the directory the launcher lives in. Everything the launcher owns —
 * the managed {@code javahome/} JRE and the default {@code luamap-run/} game
 * dir — is anchored here, never to the process working directory, so the
 * launcher behaves the same whether it's double-clicked, run from a terminal
 * elsewhere, or launched by a shortcut.
 */
final class LauncherDirs {

    /** Test/debug override: -Dluamap.launcherDir=/path */
    static final String OVERRIDE_PROPERTY = "luamap.launcherDir";

    private LauncherDirs() {
    }

    /** Directory containing the running launcher jar (or CWD in a classes-dir run). */
    static Path launcherDir() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        try {
            File self = new File(
                    LauncherDirs.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (self.isFile() && self.getName().endsWith(".jar")) {
                return self.toPath().toAbsolutePath().getParent();
            }
        } catch (URISyntaxException ignored) {
            // fall through
        }
        return Path.of("").toAbsolutePath();
    }
}
