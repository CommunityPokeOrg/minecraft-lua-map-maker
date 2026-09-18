package org.communitypoke.luamap.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic tests for platform mapping, API selection, layout, flags, plugin install. */
class IdeProvisionerTest {

    private static final String RELEASES_JSON = """
            {"IIC":[{
              "version":"2025.3","build":"253.28294.334",
              "downloads":{
                "linux":{"link":"https://download.jetbrains.com/idea/idea-2025.3.tar.gz",
                         "checksumLink":"https://download.jetbrains.com/idea/idea-2025.3.tar.gz.sha256",
                         "size":1515485267},
                "linuxARM64":{"link":"https://download.jetbrains.com/idea/idea-2025.3-aarch64.tar.gz",
                              "checksumLink":"https://download.jetbrains.com/idea/idea-2025.3-aarch64.tar.gz.sha256",
                              "size":1511136846},
                "windows":{"link":"https://download.jetbrains.com/idea/idea-2025.3.exe","size":1},
                "windowsZip":{"link":"https://download.jetbrains.com/idea/idea-2025.3.win.zip",
                              "checksumLink":"https://download.jetbrains.com/idea/idea-2025.3.win.zip.sha256",
                              "size":1526871900},
                "mac":{"link":"https://download.jetbrains.com/idea/idea-2025.3.dmg","size":1445631101},
                "macM1":{"link":"https://download.jetbrains.com/idea/idea-2025.3-aarch64.dmg","size":1433838512}
              }}]}
            """;

    // --- platform mapping ---------------------------------------------------

    @Test
    void downloadKeyMapsAllPlatforms() {
        assertEquals("linux", IdeProvisioner.downloadKey("Linux", "amd64"));
        assertEquals("linux", IdeProvisioner.downloadKey("linux", "x86_64"));
        assertEquals("linuxARM64", IdeProvisioner.downloadKey("Linux", "aarch64"));
        assertEquals("linuxARM64", IdeProvisioner.downloadKey("Linux", "arm64"));
        assertEquals("mac", IdeProvisioner.downloadKey("Mac OS X", "x86_64"));
        assertEquals("macM1", IdeProvisioner.downloadKey("Mac OS X", "aarch64"));
        assertEquals("macM1", IdeProvisioner.downloadKey("Darwin", "arm64"));
        assertEquals("windowsZip", IdeProvisioner.downloadKey("Windows 11", "amd64"));
    }

    @Test
    void downloadKeyRejectsUnsupported() {
        assertThrows(IllegalArgumentException.class,
                () -> IdeProvisioner.downloadKey("Windows 11", "aarch64")); // only .exe exists
        assertThrows(IllegalArgumentException.class,
                () -> IdeProvisioner.downloadKey("FreeBSD", "amd64"));
    }

    @Test
    void selectDownloadReadsVersionAndChecksum() {
        IdeProvisioner.Download d = IdeProvisioner.selectDownload(RELEASES_JSON, "linux");
        assertEquals("2025.3", d.version());
        assertEquals("https://download.jetbrains.com/idea/idea-2025.3.tar.gz", d.link());
        assertTrue(d.checksumLink().endsWith(".sha256"));
        assertEquals(1515485267L, d.size());
    }

    @Test
    void selectDownloadPicksPortableWindowsZipNotExe() {
        IdeProvisioner.Download d = IdeProvisioner.selectDownload(RELEASES_JSON, "windowsZip");
        assertTrue(d.link().endsWith(".win.zip"), "must use the portable zip, not the installer");
        assertFalse(d.link().endsWith(".exe"));
    }

    @Test
    void selectDownloadRejectsMissingKey() {
        assertThrows(IllegalArgumentException.class,
                () -> IdeProvisioner.selectDownload(RELEASES_JSON, "windowsARM64zip"));
        assertThrows(IllegalArgumentException.class,
                () -> IdeProvisioner.selectDownload("{\"IIC\":[]}", "linux"));
    }

    // --- managed layout -----------------------------------------------------

    @Test
    void ideHomeLivesUnderDotLuamapIde() {
        Path home = IdeProvisioner.ideHome(Path.of("/x"), "2025.3");
        assertEquals(Path.of("/x/.luamap/ide/ideaIC-2025.3"), home);
    }

    @Test
    void completionMarkerOnlyCountsWhenPresent(@TempDir Path tmp) throws IOException {
        Path home = tmp.resolve("ideaIC-1");
        Files.createDirectories(home);
        assertFalse(IdeProvisioner.isComplete(home));
        Files.writeString(home.resolve(IdeProvisioner.DONE_MARKER), "version=1\n");
        assertTrue(IdeProvisioner.isComplete(home));
    }

    @Test
    void findLauncherHandlesArchiveLayouts(@TempDir Path tmp) throws IOException {
        // direct: <home>/bin/idea.sh
        Path direct = tmp.resolve("direct");
        Files.createDirectories(direct.resolve("bin"));
        Files.writeString(direct.resolve("bin/idea.sh"), "#!/bin/sh\n");
        assertEquals(direct.resolve("bin/idea.sh"), IdeProvisioner.findLauncher(direct, "Linux"));

        // nested: <home>/idea-IC-253/bin/idea.sh
        Path nested = tmp.resolve("nested");
        Files.createDirectories(nested.resolve("idea-IC-253/bin"));
        Files.writeString(nested.resolve("idea-IC-253/bin/idea.sh"), "#!/bin/sh\n");
        assertEquals(nested.resolve("idea-IC-253/bin/idea.sh"),
                IdeProvisioner.findLauncher(nested, "Linux"));

        // windows exe
        Path win = tmp.resolve("win");
        Files.createDirectories(win.resolve("idea/bin"));
        Files.write(win.resolve("idea/bin/idea64.exe"), new byte[]{0x4D, 0x5A});
        assertEquals(win.resolve("idea/bin/idea64.exe"),
                IdeProvisioner.findLauncher(win, "Windows 11"));

        // macOS .app inside dir
        Path mac = tmp.resolve("mac");
        Files.createDirectories(mac.resolve("IntelliJ IDEA CE.app/Contents/MacOS"));
        Files.writeString(mac.resolve("IntelliJ IDEA CE.app/Contents/MacOS/idea"), "");
        assertEquals(mac.resolve("IntelliJ IDEA CE.app/Contents/MacOS/idea"),
                IdeProvisioner.findLauncher(mac, "Mac OS X"));

        assertNull(IdeProvisioner.findLauncher(tmp.resolve("nope"), "Linux"));
        Path empty = tmp.resolve("empty");
        Files.createDirectories(empty);
        assertNull(IdeProvisioner.findLauncher(empty, "Linux"));
    }

    // --- plugin install -----------------------------------------------------

    @Test
    void installPluginExtractsAndIsIdempotent(@TempDir Path tmp) throws IOException {
        Path ide = fakeIde(tmp);
        Path zip = fakePluginZip(tmp.resolve("luamap-idea-plugin-0.2.0.zip"));

        IdeProvisioner.installPluginZip(ide, zip);
        Path pluginDir = ide.resolve("plugins").resolve(IdeProvisioner.PLUGIN_ID_DIR);
        assertTrue(Files.isRegularFile(pluginDir.resolve("lib/plugin.jar")));
        assertEquals("v=0.2.0",
                Files.readString(pluginDir.resolve(".luamap-plugin-installed")).trim());

        // second call is a no-op — corrupt a file to prove it didn't re-extract
        Files.writeString(pluginDir.resolve("lib/plugin.jar"), "corrupted");
        IdeProvisioner.installPluginZip(ide, zip);
        assertEquals("corrupted", Files.readString(pluginDir.resolve("lib/plugin.jar")));
    }

    @Test
    void installPluginReinstallsWhenVersionChanges(@TempDir Path tmp) throws IOException {
        Path ide = fakeIde(tmp);
        IdeProvisioner.installPluginZip(ide, fakePluginZip(tmp.resolve("luamap-idea-plugin-0.1.0.zip")));
        Path pluginDir = ide.resolve("plugins").resolve(IdeProvisioner.PLUGIN_ID_DIR);
        IdeProvisioner.installPluginZip(ide, fakePluginZip(tmp.resolve("luamap-idea-plugin-0.2.0.zip")));
        assertEquals("v=0.2.0",
                Files.readString(pluginDir.resolve(".luamap-plugin-installed")).trim());
    }

    @Test
    void installPluginRejectsTraversalEntries(@TempDir Path tmp) throws IOException {
        Path ide = fakeIde(tmp);
        Path evil = tmp.resolve("evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(evil))) {
            z.putNextEntry(new ZipEntry("../escape.txt"));
            z.write("x".getBytes());
            z.closeEntry();
        }
        assertThrows(IOException.class, () -> IdeProvisioner.installPluginZip(ide, evil));
        assertFalse(Files.exists(ide.getParent().resolve("escape.txt")));
    }

    // --- flags --------------------------------------------------------------

    @Test
    void flagsParse() {
        assertEquals(IdeFlags.Mode.NONE, IdeFlags.parse(new String[]{"--server"}).mode());
        assertEquals(IdeFlags.Mode.SETUP,
                IdeFlags.parse(new String[]{"--setup-ide"}).mode());
        assertEquals(IdeFlags.Mode.LAUNCH,
                IdeFlags.parse(new String[]{"--ide", "--username", "W"}).mode());
        assertEquals(Path.of("/opt/idea"),
                IdeFlags.parse(new String[]{"--ide", "--idePath", "/opt/idea"}).idePath());
        assertEquals(IdeFlags.Mode.SETUP,
                IdeFlags.parse(new String[]{"--idePath", "/x", "--setup-ide"}).mode());
        assertThrows(IllegalArgumentException.class,
                () -> IdeFlags.parse(new String[]{"--ide", "--setup-ide"}));
        assertThrows(IllegalArgumentException.class,
                () -> IdeFlags.parse(new String[]{"--idePath"}));
    }

    @Test
    void pluginVersionFromName() {
        assertEquals("0.2.0",
                IdeProvisioner.pluginVersion(Path.of("luamap-idea-plugin-0.2.0.zip")));
        assertEquals("bundled", IdeProvisioner.pluginVersion(Path.of("plugin-bundled.zip")));
    }

    private static Path fakeIde(Path tmp) throws IOException {
        Path ide = tmp.resolve("fake-ide");
        Files.createDirectories(ide.resolve("bin"));
        Files.writeString(ide.resolve("bin/idea.sh"), "#!/bin/sh\n");
        return ide;
    }

    private static Path fakePluginZip(Path zip) throws IOException {
        try (OutputStream fos = Files.newOutputStream(zip);
             ZipOutputStream z = new ZipOutputStream(fos)) {
            z.putNextEntry(new ZipEntry("luamap-idea-plugin/lib/"));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("luamap-idea-plugin/lib/plugin.jar"));
            z.write("fake-jar".getBytes());
            z.closeEntry();
        }
        return zip;
    }
}
