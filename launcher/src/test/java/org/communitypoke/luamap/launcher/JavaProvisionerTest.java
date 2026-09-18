package org.communitypoke.luamap.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaProvisionerTest {

    @TempDir
    Path tmp;

    // --- platform mapping ------------------------------------------------------

    @Test
    void adoptiumOsMapsCommonNames() {
        assertEquals("linux", JavaProvisioner.adoptiumOs("Linux"));
        assertEquals("linux", JavaProvisioner.adoptiumOs("GNU/Linux"));
        assertEquals("mac", JavaProvisioner.adoptiumOs("Mac OS X"));
        assertEquals("mac", JavaProvisioner.adoptiumOs("Darwin"));
        assertEquals("windows", JavaProvisioner.adoptiumOs("Windows 11"));
        assertThrows(IllegalArgumentException.class, () -> JavaProvisioner.adoptiumOs("SunOS"));
    }

    @Test
    void adoptiumArchMapsCommonNames() {
        assertEquals("x64", JavaProvisioner.adoptiumArch("amd64"));
        assertEquals("x64", JavaProvisioner.adoptiumArch("x86_64"));
        assertEquals("aarch64", JavaProvisioner.adoptiumArch("aarch64"));
        assertEquals("aarch64", JavaProvisioner.adoptiumArch("arm64"));
        assertEquals("x86", JavaProvisioner.adoptiumArch("i386"));
        assertThrows(IllegalArgumentException.class, () -> JavaProvisioner.adoptiumArch("riscv64"));
    }

    @Test
    void temurinUrlTargetsJre17Ga() {
        assertEquals(
                "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jre/hotspot/normal/eclipse",
                JavaProvisioner.temurinUrl("linux", "x64"));
        assertEquals(
                "https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jre/hotspot/normal/eclipse",
                JavaProvisioner.temurinUrl("mac", "aarch64"));
    }

    // --- version parsing --------------------------------------------------------

    @Test
    void parsesMajorVersions() {
        assertEquals(17, JavaProvisioner.parseMajorVersion(
                "openjdk version \"17.0.12\" 2024-07-16\nOpenJDK Runtime Environment Temurin-17.0.12+7"));
        assertEquals(17, JavaProvisioner.parseMajorVersion("java version \"17\" "));
        assertEquals(8, JavaProvisioner.parseMajorVersion("java version \"1.8.0_402\""));
        assertEquals(21, JavaProvisioner.parseMajorVersion("openjdk version \"21.0.3\""));
        assertNull(JavaProvisioner.parseMajorVersion("garbage"));
        assertNull(JavaProvisioner.parseMajorVersion(""));
    }

    // --- java discovery ------------------------------------------------------------

    @Test
    void findJavaHandlesLayouts() throws IOException {
        // direct bin/java
        Path a = tmp.resolve("a");
        Path direct = fakeJava(a.resolve("bin"));
        assertEquals(direct, JavaProvisioner.findJava(a));

        // nested jdk dir (typical Temurin extraction)
        Path b = tmp.resolve("b");
        Path nested = fakeJava(b.resolve("jdk-17.0.12+7").resolve("bin"));
        assertEquals(nested, JavaProvisioner.findJava(b));

        // macOS bundle layout
        Path c = tmp.resolve("c");
        Path mac = fakeJava(c.resolve("jdk-17.jdk").resolve("Contents").resolve("Home").resolve("bin"));
        assertEquals(mac, JavaProvisioner.findJava(c));

        // nothing
        Files.createDirectories(tmp.resolve("empty"));
        assertNull(JavaProvisioner.findJava(tmp.resolve("empty")));
        assertNull(JavaProvisioner.findJava(tmp.resolve("missing")));
    }

    @Test
    void findJavaPrefersDirectOverNested() throws IOException {
        Path h = tmp.resolve("h");
        Path direct = fakeJava(h.resolve("bin"));
        fakeJava(h.resolve("jdk-17.0.1").resolve("bin"));
        assertEquals(direct, JavaProvisioner.findJava(h));
    }

    // --- validation against a fake java ----------------------------------------------

    @Test
    void majorVersionRunsExecutable() throws IOException {
        Path bin = tmp.resolve("bin");
        Path java = fakeJavaWithVersion(bin, "openjdk version \"17.0.12\" 2024-07-16");
        assertEquals(17, JavaProvisioner.majorVersion(java));
    }

    @Test
    void majorVersionRejectsWrongVersionAndBrokenExec() throws IOException {
        Path bin = tmp.resolve("bin");
        Path java21 = fakeJavaWithVersion(bin, "openjdk version \"21.0.3\" 2024-04-16");
        assertEquals(21, JavaProvisioner.majorVersion(java21));
        assertNull(JavaProvisioner.majorVersion(bin.resolve("does-not-exist")));
    }

    @Test
    void ensureJava17AcceptsPreinstalledRuntime() throws Exception {
        Path javahome = tmp.resolve(JavaProvisioner.DIR_NAME);
        Path java = fakeJavaWithVersion(javahome.resolve("jdk-17.0.12").resolve("bin"),
                "openjdk version \"17.0.12\" 2024-07-16");
        assertEquals(java, JavaProvisioner.ensureJava17(tmp));
    }

    // --- helpers -----------------------------------------------------------------

    private Path fakeJava(Path bin) throws IOException {
        Files.createDirectories(bin);
        Path java = bin.resolve("java");
        Files.writeString(java, "#!/bin/sh\nexit 0\n");
        makeExec(java);
        return java;
    }

    private Path fakeJavaWithVersion(Path bin, String versionLine) throws IOException {
        Files.createDirectories(bin);
        Path java = bin.resolve("java");
        Files.writeString(java, "#!/bin/sh\necho '" + versionLine + "' 1>&2\n");
        makeExec(java);
        return java;
    }

    private void makeExec(Path p) throws IOException {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            // Windows CI — exec bits don't apply
        }
    }
}
