package org.communitypoke.luamap.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Provisions a managed IntelliJ IDEA Community install plus the LuaMap plugin
 * under {@code <launcherDir>/.luamap/}, mirroring how {@link JavaProvisioner}
 * manages {@code javahome/}.
 *
 * <p>Layout:
 * <pre>
 *   .luamap/ide/ideaIC-&lt;version&gt;/       extracted IDE (per-version dir)
 *   .luamap/ide/ideaIC-&lt;version&gt;/.done   atomic completion marker
 *   .luamap/ide/plugin/&lt;version&gt;.zip     downloaded plugin (when not bundled)
 * </pre>
 *
 * <p>Downloads come from the JetBrains releases API
 * ({@code data.services.jetbrains.com/products/releases?code=IIC&latest=true})
 * and are verified against the {@code .sha256} the API advertises next to each
 * artifact. Extraction: tar.gz/zip via {@link Archives} (traversal-safe);
 * macOS dmg via {@code hdiutil}. A {@code .done} marker is only written after
 * the whole provision+plugin-install completes, so interrupted runs retry
 * cleanly.
 */
final class IdeProvisioner {

    static final String IDE_ROOT = ".luamap/ide";
    static final String RELEASES_API =
            "https://data.services.jetbrains.com/products/releases?code=IIC&latest=true&type=release";
    static final String DONE_MARKER = ".done";
    static final String PLUGIN_ID_DIR = "luamap-idea-plugin";
    /** Resource path inside the launcher jar when the plugin zip is bundled. */
    static final String BUNDLED_PLUGIN = "/bundled-plugins/luamap-idea-plugin.zip";
    static final String PLUGIN_RELEASES_BASE =
            "https://github.com/CommunityPokeOrg/minecraft-lua-map-maker/releases/download";

    private static final int MAX_ATTEMPTS = 3;

    private IdeProvisioner() {
    }

    // --- platform mapping (pure, unit-tested) ----------------------------------

    /** Releases-API download key for an {@code os.name}/{@code os.arch} pair. */
    static String downloadKey(String osName, String osArch) {
        String n = osName.toLowerCase();
        String a = osArch.toLowerCase();
        boolean arm = a.equals("aarch64") || a.equals("arm64") || a.startsWith("arm");
        if (n.contains("mac") || n.contains("darwin")) {
            return arm ? "macM1" : "mac";
        }
        if (n.contains("win")) {
            if (arm) {
                throw new IllegalArgumentException(
                        "No portable Windows ARM64 IntelliJ build is published — "
                                + "install IDEA manually and pass --idePath");
            }
            return "windowsZip"; // the portable zip, not the .exe installer
        }
        if (n.contains("linux")) {
            return arm ? "linuxARM64" : "linux";
        }
        throw new IllegalArgumentException("Unsupported OS for IDE provisioning: " + osName);
    }

    /**
     * Picks the download entry for {@code key} out of the releases-API JSON
     * ({@code {"IIC":[{...,"downloads":{...}}]}). Returns {version, link,
     * checksumLink, size}.
     */
    static Download selectDownload(String releasesJson, String key) {
        JsonObject root = JsonParser.parseString(releasesJson).getAsJsonObject();
        JsonArray releases = root.getAsJsonArray("IIC");
        if (releases == null || releases.isEmpty()) {
            throw new IllegalArgumentException("releases API returned no IIC entries");
        }
        JsonObject latest = releases.get(0).getAsJsonObject();
        JsonObject dl = latest.getAsJsonObject("downloads").getAsJsonObject(key);
        if (dl == null) {
            throw new IllegalArgumentException("no '" + key + "' download in release "
                    + latest.get("version").getAsString());
        }
        return new Download(
                latest.get("version").getAsString(),
                dl.get("link").getAsString(),
                dl.has("checksumLink") ? dl.get("checksumLink").getAsString() : null,
                dl.has("size") ? dl.get("size").getAsLong() : -1);
    }

    record Download(String version, String link, String checksumLink, long size) {
    }

    // --- layout (pure, unit-tested) --------------------------------------------

    static Path ideHome(Path launcherDir, String version) {
        return launcherDir.resolve(IDE_ROOT).resolve("ideaIC-" + version);
    }

    static boolean isComplete(Path home) {
        return Files.isRegularFile(home.resolve(DONE_MARKER));
    }

    /**
     * Locates the IDE launch script/exe under an install dir, tolerating the
     * single nested directory archives produce (e.g. {@code idea-IC-N/bin/idea.sh}).
     * Deterministic: direct check first, then children sorted by name.
     */
    static Path findLauncher(Path home, String osName) {
        if (home == null || !Files.exists(home)) {
            return null;
        }
        Path direct = launcherAt(home, osName);
        if (direct != null) {
            return direct;
        }
        List<Path> children;
        try (Stream<Path> s = Files.list(home)) {
            children = s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return null;
        }
        for (Path child : children) {
            Path nested = launcherAt(child, osName);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static Path launcherAt(Path dir, String osName) {
        String n = osName.toLowerCase();
        Path p;
        if (n.contains("win")) {
            p = dir.resolve("bin").resolve("idea64.exe");
        } else if (n.contains("mac") || n.contains("darwin")) {
            p = dir.resolve("Contents").resolve("MacOS").resolve("idea"); // inside .app
            if (!Files.isRegularFile(p)) {
                // dir may be the parent holding "IntelliJ IDEA CE.app"
                try (Stream<Path> s = Files.list(dir)) {
                    for (Path child : (Iterable<Path>) s.filter(Files::isDirectory)
                            .sorted()::iterator) {
                        Path c = child.resolve("Contents").resolve("MacOS").resolve("idea");
                        if (Files.isRegularFile(c)) {
                            return c;
                        }
                    }
                } catch (IOException e) {
                    return null;
                }
                return null;
            }
        } else {
            p = dir.resolve("bin").resolve("idea.sh");
        }
        return Files.isRegularFile(p) ? p : null;
    }

    // --- provisioning -----------------------------------------------------------

    /**
     * Ensures a managed IDE install exists under {@code launcherDir/.luamap/ide}
     * and the LuaMap plugin is installed into it. When {@code explicit} is
     * non-null, that existing install is used instead (plugin still installed).
     * Returns the IDE install dir.
     */
    static Path ensureIde(Path launcherDir, Path explicit)
            throws IOException, InterruptedException {
        Path ideRoot = launcherDir.resolve(IDE_ROOT);

        if (explicit != null) {
            Path dir = explicit.toAbsolutePath();
            if (!Files.isDirectory(dir)) {
                throw new IOException("--idePath does not exist or is not a directory: " + dir);
            }
            if (findLauncher(dir, System.getProperty("os.name", "")) == null) {
                throw new IOException("--idePath does not look like an IntelliJ install "
                        + "(no bin/idea.sh, bin/idea64.exe, or .app/Contents/MacOS/idea): " + dir);
            }
            installPlugin(dir, launcherDir);
            return dir;
        }

        // Reuse an existing complete install — newest version wins.
        Path existing = newestComplete(ideRoot);
        if (existing != null) {
            System.out.println("Using managed IDE at " + existing);
            installPlugin(existing, launcherDir);
            return existing;
        }

        Download dl = selectDownload(Http.getString(RELEASES_API),
                downloadKey(System.getProperty("os.name", ""),
                        System.getProperty("os.arch", "")));
        Path home = ideHome(launcherDir, dl.version());
        if (isComplete(home)) {
            installPlugin(home, launcherDir);
            return home;
        }

        provision(launcherDir, ideRoot, home, dl);
        installPlugin(home, launcherDir);
        markDone(home, dl.version());
        return home;
    }

    private static Path newestComplete(Path ideRoot) throws IOException {
        if (!Files.isDirectory(ideRoot)) {
            return null;
        }
        try (Stream<Path> s = Files.list(ideRoot)) {
            return s.filter(d -> Files.isDirectory(d) && isComplete(d))
                    .max(java.util.Comparator.comparing(d -> d.getFileName().toString()))
                    .orElse(null);
        }
    }

    private static void provision(Path launcherDir, Path ideRoot, Path home, Download dl)
            throws IOException, InterruptedException {
        Files.createDirectories(ideRoot);
        Path archive = ideRoot.resolve("ideaIC-" + dl.version() + ".download");
        Path staging = ideRoot.resolve("ideaIC-" + dl.version() + ".staging");

        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                System.out.println("Downloading IntelliJ IDEA " + dl.version()
                        + " — attempt " + attempt);
                Http.download(dl.link(), archive);
                verifySize(archive, dl.size());
                verifySha256(archive, dl.checksumLink());
                System.out.println("Extracting IDE…");
                extractIde(archive, staging);
                JavaProvisioner.deleteTree(home);
                Files.move(staging, home, StandardCopyOption.ATOMIC_MOVE);
                Files.deleteIfExists(archive);
                makeExecutables(home);
                return;
            } catch (IOException e) {
                last = e;
                System.err.println("  attempt " + attempt + " failed: " + e.getMessage());
                JavaProvisioner.deleteTree(staging);
                Files.deleteIfExists(archive);
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(3000L * attempt);
                }
            }
        }
        throw new IOException("Could not provision IntelliJ IDEA " + dl.version()
                + " after " + MAX_ATTEMPTS + " attempts. Install IDEA manually and"
                + " pass --idePath <dir>.", last);
    }

    /**
     * tar.gz / zip / dmg. ZIPs go through {@link Archives} (traversal-safe).
     * tar.gz prefers the system {@code tar} — IntelliJ tarballs contain GNU
     * sparse and pax entries that a minimal hand-rolled untar can't parse;
     * bsdtar/GNU tar also refuse absolute-path and {@code ..} entries on
     * extraction. Falls back to {@link Archives} when {@code tar} is absent.
     */
    private static void extractIde(Path archive, Path staging) throws IOException {
        String name = archive.getFileName().toString();
        // sniff the real format (the temp name carries no extension)
        try (InputStream in = Files.newInputStream(archive)) {
            in.mark(4);
            int b0 = in.read(), b1 = in.read();
            if (b0 == 0x1F && b1 == 0x8B) {
                extractTarGz(archive, staging);
                return;
            }
            if (b0 == 'P' && b1 == 'K') {
                Archives.extract(archive, staging);
                return;
            }
            // anything else on macOS is treated as a dmg
            if (!"osx".equals(Os.name())) {
                throw new IOException("Unrecognized IDE archive format: " + name);
            }
        }
        extractDmg(archive, staging);
    }

    private static void extractTarGz(Path archive, Path staging) throws IOException {
        Files.createDirectories(staging);
        try {
            execChecked("tar", "-xzf", archive.toAbsolutePath().toString(),
                    "-C", staging.toAbsolutePath().toString());
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Cannot run program")) {
                Archives.extract(archive, staging); // embedded fallback
                return;
            }
            throw e;
        }
    }

    private static void extractDmg(Path dmg, Path dest) throws IOException {
        Files.createDirectories(dest);
        Path mount = Files.createTempDirectory("luamap-dmg-");
        try {
            execChecked("hdiutil", "attach", "-nobrowse", "-readonly",
                    "-mountpoint", mount.toString(), dmg.toString());
            Path app = findAppBundle(mount);
            if (app == null) {
                throw new IOException("no .app bundle found in " + dmg);
            }
            execChecked("ditto", app.toString(), dest.resolve(app.getFileName().toString()).toString());
        } finally {
            try {
                execChecked("hdiutil", "detach", mount.toString(), "-force");
            } catch (IOException ignored) {
                // best-effort unmount
            }
            Files.deleteIfExists(mount);
        }
    }

    private static Path findAppBundle(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(d -> d.getFileName().toString().endsWith(".app"))
                    .findFirst().orElse(null);
        }
    }

    private static void execChecked(String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try {
            String out = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException(String.join(" ", cmd) + " exited " + code
                        + ": " + out.trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted running " + cmd[0], e);
        }
    }

    // --- plugin install -----------------------------------------------------------

    /**
     * Installs the LuaMap plugin zip into {@code <ide>/plugins/}. Idempotent:
     * a {@code .luamap-plugin-installed} marker inside the plugin dir records
     * the installed version; reinstall only happens when it changes or the dir
     * is absent. Zip entries are traversal-guarded.
     */
    static void installPlugin(Path ideDir, Path launcherDir) throws IOException {
        installPluginZip(ideDir, pluginZip(launcherDir));
    }

    /** Package-private seam for tests — the same logic with the zip supplied. */
    static void installPluginZip(Path ideDir, Path zip) throws IOException {
        String marker = "v=" + pluginVersion(zip);
        Path pluginDir = ideDir.resolve("plugins").resolve(PLUGIN_ID_DIR);
        Path markerFile = pluginDir.resolve(".luamap-plugin-installed");
        if (Files.isRegularFile(markerFile)
                && Files.readString(markerFile).trim().equals(marker)) {
            return; // already installed at this version
        }
        Files.createDirectories(pluginDir.getParent());
        // stage the extraction, then swap — a partial unzip never leaves a
        // half-installed plugin behind. The zip's root dir is the plugin dir
        // itself (luamap-idea-plugin/lib/...), so it becomes plugins/<id>/.
        Path staging = pluginDir.getParent().resolve(".staging-" + PLUGIN_ID_DIR);
        JavaProvisioner.deleteTree(staging);
        Archives.extract(zip, staging);
        Path inner = staging.resolve(PLUGIN_ID_DIR);
        Path extracted = Files.isDirectory(inner) ? inner : staging;
        JavaProvisioner.deleteTree(pluginDir);
        try {
            Files.move(extracted, pluginDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(extracted, pluginDir);
        }
        JavaProvisioner.deleteTree(staging);
        Files.writeString(markerFile, marker);
        System.out.println("LuaMap plugin installed into " + pluginDir);
    }

    /** Bundled resource if present, else download from the project's GitHub releases. */
    private static Path pluginZip(Path launcherDir) throws IOException {
        String implVersion = IdeProvisioner.class.getPackage().getImplementationVersion();
        try (InputStream in = IdeProvisioner.class.getResourceAsStream(BUNDLED_PLUGIN)) {
            if (in != null) {
                String name = implVersion != null
                        ? "luamap-idea-plugin-" + implVersion + ".zip" : "plugin-bundled.zip";
                Path out = launcherDir.resolve(".luamap").resolve(name);
                Files.createDirectories(out.getParent());
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                return out;
            }
        }
        // fallback: GitHub release asset matching the launcher's own version
        if (implVersion == null) {
            throw new IOException("LuaMap plugin zip is not bundled and the launcher "
                    + "version is unknown — cannot pick a release asset");
        }
        String name = "luamap-idea-plugin-" + implVersion + ".zip";
        String url = PLUGIN_RELEASES_BASE + "/v" + implVersion + "/" + name;
        Path dest = launcherDir.resolve(IDE_ROOT).resolve("plugin").resolve(name);
        try {
            Http.download(url, dest);
            verifySha256(dest, url + ".sha256");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted downloading plugin", e);
        }
        return dest;
    }

    /** Version string embedded in a bundled/downloaded zip — from its filename. */
    static String pluginVersion(Path zip) {
        String name = zip.getFileName().toString();
        if (name.startsWith("luamap-idea-plugin-") && name.endsWith(".zip")) {
            return name.substring("luamap-idea-plugin-".length(), name.length() - 4);
        }
        return "bundled";
    }

    // --- verification ---------------------------------------------------------

    private static void verifySize(Path file, long expected) throws IOException {
        if (expected > 0 && Files.size(file) != expected) {
            throw new IOException("size mismatch for " + file + ": got "
                    + Files.size(file) + ", expected " + expected);
        }
    }

    private static void verifySha256(Path file, String checksumLink) throws IOException {
        if (checksumLink == null) {
            return;
        }
        String expected;
        try {
            expected = Http.getString(checksumLink).trim().split("\\s+")[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching checksum", e);
        } catch (IOException e) {
            throw new IOException("could not fetch sha256 for " + file + ": " + e.getMessage(), e);
        }
        String actual;
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            actual = HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("sha256 mismatch for " + file + " — refusing to use it "
                    + "(got " + actual + ", expected " + expected + ")");
        }
    }

    /** {@code bin/*} and .app launch binaries executable on POSIX. */
    private static void makeExecutables(Path home) throws IOException {
        if (Os.isWindows()) {
            return;
        }
        var perms = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");
        try (Stream<Path> s = Files.walk(home)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String n = p.getFileName().toString();
                boolean inBin = p.getParent() != null
                        && "bin".equals(p.getParent().getFileName().toString());
                boolean macLauncher = "MacOS".equals(
                        p.getParent() != null ? p.getParent().getFileName().toString() : "");
                if (inBin || macLauncher || n.endsWith(".sh") || n.equals("fsnotifier")) {
                    try {
                        Files.setPosixFilePermissions(p, perms);
                    } catch (UnsupportedOperationException ignored) {
                    }
                }
            }
        }
    }

    private static void markDone(Path home, String version) throws IOException {
        Path tmp = home.resolve(DONE_MARKER + ".tmp");
        Files.writeString(tmp, "version=" + version + "\n");
        Files.move(tmp, home.resolve(DONE_MARKER), StandardCopyOption.ATOMIC_MOVE);
    }

    // --- launch -----------------------------------------------------------------

    /** Launches the IDE detached and returns. Throws if no launcher binary is found. */
    static void launch(Path ideDir) throws IOException {
        Path exe = findLauncher(ideDir, System.getProperty("os.name", ""));
        if (exe == null) {
            throw new IOException("No IDE launcher found under " + ideDir);
        }
        if ("osx".equals(Os.name()) && exe.toString().contains(".app")) {
            Path app = exe;
            while (app != null && !app.toString().endsWith(".app")) {
                app = app.getParent();
            }
            new ProcessBuilder("open", app.toString()).start();
        } else {
            new ProcessBuilder(exe.toAbsolutePath().toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        }
        System.out.println("IDE launched: " + exe);
    }
}
