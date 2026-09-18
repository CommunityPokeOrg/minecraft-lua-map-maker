package org.communitypoke.luamap.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Guarantees a Java 17 runtime in {@code <launcherDir>/javahome/} and returns
 * the path to its {@code java} executable. The managed JRE always wins over
 * whatever Java runs the launcher itself — the game is never started with a
 * too-new JDK (e.g. Java 26 / class version 70), which Fabric/ASM reject.
 *
 * <p>If the directory is missing or its Java isn't version 17, a Temurin
 * (Adoptium) JRE 17 for the current OS/arch is downloaded, safely extracted
 * into a staging dir, verified, and atomically swapped in. Partial downloads
 * and stale staging dirs are cleaned up.
 */
final class JavaProvisioner {

    static final String DIR_NAME = "javahome";
    static final String STAGING_NAME = "javahome.staging";
    static final String DOWNLOAD_NAME = "javahome-download.bin";

    static final int REQUIRED_MAJOR = 17;
    private static final int MAX_ATTEMPTS = 3;
    private static final int VERSION_TIMEOUT_SECS = 15;

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?", Pattern.CASE_INSENSITIVE);

    private JavaProvisioner() {
    }

    /**
     * Returns the path to a working Java 17 {@code java} executable under
     * {@code launcherDir/javahome}, downloading one if needed.
     */
    static Path ensureJava17(Path launcherDir) throws IOException, InterruptedException {
        Path home = launcherDir.resolve(DIR_NAME);
        cleanup(launcherDir);

        Path java = findJava(home);
        if (java != null) {
            Integer major = majorVersion(java);
            if (major != null && major == REQUIRED_MAJOR) {
                System.out.println("Using Java " + major + " at " + java);
                return java;
            }
            System.out.println("javahome contains an incompatible Java "
                    + (major == null ? "(unrecognized)" : major) + " — replacing it");
            deleteTree(home);
            java = null;
        }

        if (java == null) {
            provision(launcherDir, home);
            java = findJava(home);
        }
        if (java == null) {
            throw new IOException("Provisioned javahome contains no java executable");
        }
        Integer major = majorVersion(java);
        if (major == null || major != REQUIRED_MAJOR) {
            deleteTree(home);
            throw new IOException("Downloaded Java reported major version "
                    + major + " instead of " + REQUIRED_MAJOR);
        }
        System.out.println("Provisioned Java " + major + " at " + java);
        return java;
    }

    // --- discovery ------------------------------------------------------------

    /**
     * Finds a {@code java} executable inside {@code home}, accepting the
     * layouts Temurin archives produce: {@code bin/java} directly, one nested
     * JDK directory ({@code jdk-17.0.x/bin/java}), and the macOS bundle layout
     * ({@code jdk-17.0.x/Contents/Home/bin/java}). Deterministic: direct match
     * first, then children sorted by name.
     */
    static Path findJava(Path home) {
        if (!Files.isDirectory(home)) {
            return null;
        }
        String exe = Os.isWindows() ? "java.exe" : "java";

        Path direct = home.resolve("bin").resolve(exe);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path macDirect = home.resolve("Contents").resolve("Home").resolve("bin").resolve(exe);
        if (Files.isRegularFile(macDirect)) {
            return macDirect;
        }
        List<Path> children;
        try (Stream<Path> s = Files.list(home)) {
            children = s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return null;
        }
        for (Path child : children) {
            Path nested = child.resolve("bin").resolve(exe);
            if (Files.isRegularFile(nested)) {
                return nested;
            }
            Path macNested = child.resolve("Contents").resolve("Home").resolve("bin").resolve(exe);
            if (Files.isRegularFile(macNested)) {
                return macNested;
            }
        }
        return null;
    }

    /**
     * Runs {@code java -version} and returns the major version, or null if the
     * executable can't run or doesn't report one.
     */
    static Integer majorVersion(Path java) {
        try {
            Process p = new ProcessBuilder(java.toAbsolutePath().toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes());
            }
            if (!p.waitFor(VERSION_TIMEOUT_SECS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return parseMajorVersion(out);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** Parses {@code openjdk version "17.0.12"} / {@code java version "1.8.0"} output. */
    static Integer parseMajorVersion(String versionOutput) {
        Matcher m = VERSION_PATTERN.matcher(versionOutput);
        if (!m.find()) {
            return null;
        }
        int first = Integer.parseInt(m.group(1));
        if (first == 1 && m.group(2) != null) {
            return Integer.parseInt(m.group(2)); // "1.8.0" → 8
        }
        return first;
    }

    // --- Temurin platform mapping (pure, unit-tested) ---------------------------

    /** Maps an {@code os.name} value to the Adoptium API platform token. */
    static String adoptiumOs(String osName) {
        String n = osName.toLowerCase();
        // mac check first: "darwin" contains the substring "win"
        if (n.contains("mac") || n.contains("darwin")) {
            return "mac";
        }
        if (n.contains("win")) {
            return "windows";
        }
        if (n.contains("linux")) {
            return "linux";
        }
        throw new IllegalArgumentException("Unsupported OS for auto-provision: " + osName);
    }

    /** Maps an {@code os.arch} value to the Adoptium API architecture token. */
    static String adoptiumArch(String osArch) {
        String a = osArch.toLowerCase();
        if (a.equals("x86_64") || a.equals("amd64")) {
            return "x64";
        }
        if (a.equals("aarch64") || a.equals("arm64") || a.startsWith("arm")) {
            return "aarch64";
        }
        if (a.equals("x86") || a.equals("i386") || a.equals("i586") || a.equals("i686")) {
            return "x86";
        }
        throw new IllegalArgumentException("Unsupported arch for auto-provision: " + osArch);
    }

    /** Adoptium API binary endpoint for a Temurin 17 JRE. */
    static String temurinUrl(String os, String arch) {
        return "https://api.adoptium.net/v3/binary/latest/" + REQUIRED_MAJOR
                + "/ga/" + os + "/" + arch + "/jre/hotspot/normal/eclipse";
    }

    // --- provisioning -----------------------------------------------------------

    private static void provision(Path launcherDir, Path home)
            throws IOException, InterruptedException {
        String os = adoptiumOs(System.getProperty("os.name", ""));
        String arch = adoptiumArch(System.getProperty("os.arch", ""));
        String url = temurinUrl(os, arch);
        Path archive = launcherDir.resolve(DOWNLOAD_NAME);
        Path staging = launcherDir.resolve(STAGING_NAME);

        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                System.out.println("Downloading Temurin JRE " + REQUIRED_MAJOR
                        + " (" + os + "/" + arch + ") — attempt " + attempt);
                Http.download(url, archive);
                System.out.println("Extracting runtime…");
                Archives.extract(archive, staging);
                deleteTree(home);
                Files.move(staging, home, StandardCopyOption.ATOMIC_MOVE);
                Files.deleteIfExists(archive);
                makeExecutables(home);
                return;
            } catch (IOException e) {
                last = e;
                System.err.println("  attempt " + attempt + " failed: " + e.getMessage());
                deleteTree(staging);
                Files.deleteIfExists(archive);
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(2000L * attempt);
                }
            }
        }
        throw new IOException("Could not provision a Java " + REQUIRED_MAJOR
                + " runtime for " + os + "/" + arch + " after " + MAX_ATTEMPTS
                + " attempts. Place a Temurin JRE " + REQUIRED_MAJOR
                + " in " + home + " manually.", last);
    }

    /** Marks extracted {@code bin/*} and helper binaries executable on POSIX systems. */
    private static void makeExecutables(Path home) throws IOException {
        if (Os.isWindows()) {
            return;
        }
        var perms = PosixFilePermissions.fromString("rwxr-xr-x");
        try (Stream<Path> s = Files.walk(home)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                boolean inBin = p.getParent() != null
                        && "bin".equals(p.getParent().getFileName().toString());
                if (inBin || name.equals("jspawnhelper") || name.equals("jexec")) {
                    try {
                        Files.setPosixFilePermissions(p, perms);
                    } catch (UnsupportedOperationException ignored) {
                        // non-POSIX filesystem — nothing to do
                    }
                }
            }
        }
    }

    /** Removes leftover staging/download artifacts from an interrupted previous run. */
    private static void cleanup(Path launcherDir) throws IOException {
        deleteTree(launcherDir.resolve(STAGING_NAME));
        Files.deleteIfExists(launcherDir.resolve(DOWNLOAD_NAME));
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }
}
