package org.communitypoke.luamap.launcher;

import java.nio.file.Path;

/**
 * Parsed IDE-related launcher flags (kept separate from {@link Main} so flag
 * semantics are unit-testable without booting anything).
 *
 * <ul>
 *   <li>{@code --setup-ide}: provision the IDE + install the LuaMap plugin, print
 *       the install path, exit. Never starts the game.</li>
 *   <li>{@code --ide}: same provisioning, then launch the IDE, exit. Never
 *       starts the game — run the launcher without it for the game.</li>
 *   <li>{@code --idePath DIR}: use an existing IntelliJ install/dir instead of
 *       the managed one (the plugin is still installed into it).</li>
 * </ul>
 */
final class IdeFlags {

    enum Mode { NONE, SETUP, LAUNCH }

    record Result(Mode mode, Path idePath) {
    }

    static final Result NONE = new Result(Mode.NONE, null);

    /** Throws {@link IllegalArgumentException} on bad combinations/values. */
    static Result parse(String[] args) {
        Mode mode = Mode.NONE;
        Path idePath = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--setup-ide" -> mode = merge(mode, Mode.SETUP);
                case "--ide" -> mode = merge(mode, Mode.LAUNCH);
                case "--idePath" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--idePath requires a directory");
                    }
                    idePath = Path.of(args[++i]);
                }
                default -> {
                }
            }
        }
        return new Result(mode, idePath);
    }

    private static Mode merge(Mode a, Mode b) {
        if (a != Mode.NONE && a != b) {
            throw new IllegalArgumentException(
                    "--setup-ide and --ide are mutually exclusive");
        }
        return b;
    }
}
