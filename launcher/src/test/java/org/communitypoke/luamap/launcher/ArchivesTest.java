package org.communitypoke.luamap.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchivesTest {

    @TempDir
    Path tmp;

    @Test
    void extractsZip() throws IOException {
        Path zip = tmp.resolve("x.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("jdk-17/bin/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("jdk-17/bin/java"));
            zos.write("binary".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path dest = tmp.resolve("out");
        Archives.extract(zip, dest);
        assertEquals("binary", Files.readString(dest.resolve("jdk-17/bin/java")));
    }

    @Test
    void extractsTarGz() throws IOException {
        Path tgz = tmp.resolve("x.tar.gz");
        try (OutputStream fos = Files.newOutputStream(tgz);
             GZIPOutputStream gz = new GZIPOutputStream(fos)) {
            gz.write(tarHeader("jdk-17/bin/", '5', 0755, 0));
            gz.write(tarHeader("jdk-17/bin/java", '0', 0755, 5));
            gz.write("hello".getBytes(StandardCharsets.UTF_8));
            gz.write(new byte[512 - 5]); // pad to 512
            gz.write(new byte[1024]); // end blocks
        }
        Path dest = tmp.resolve("tout");
        Archives.extract(tgz, dest);
        Path java = dest.resolve("jdk-17/bin/java");
        assertEquals("hello", Files.readString(java));
        assertTrue(Files.isDirectory(dest.resolve("jdk-17/bin")));
    }

    @Test
    void tarAppliesExecMode() throws IOException {
        Path tgz = tmp.resolve("m.tar.gz");
        try (OutputStream fos = Files.newOutputStream(tgz);
             GZIPOutputStream gz = new GZIPOutputStream(fos)) {
            gz.write(tarHeader("run.sh", '0', 0750, 4));
            gz.write("boom".getBytes(StandardCharsets.UTF_8));
            gz.write(new byte[512 - 4]);
            gz.write(new byte[1024]);
        }
        Path dest = tmp.resolve("mout");
        Archives.extract(tgz, dest);
        var perms = Files.getPosixFilePermissions(dest.resolve("run.sh"));
        assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE),
                "expected exec bit, got " + perms);
    }

    @Test
    void rejectsTraversal() throws IOException {
        Path zip = tmp.resolve("evil.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("../escape.txt"));
            zos.write("x".getBytes());
            zos.closeEntry();
        }
        assertThrows(IOException.class, () -> Archives.extract(zip, tmp.resolve("ez")));
    }

    @Test
    void rejectsUnknownFormat() throws IOException {
        Path junk = tmp.resolve("junk.bin");
        Files.write(junk, "not an archive".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> Archives.extract(junk, tmp.resolve("jz")));
    }

    @Test
    void tarFileEntryReplacesExistingDirectory() throws IOException {
        // GNU tar semantics: a later non-dir entry overwrites a dir created by
        // an earlier entry — real archives (IntelliJ's tarball) contain this.
        Path tgz = tmp.resolve("conflict.tar.gz");
        try (OutputStream fos = Files.newOutputStream(tgz);
             GZIPOutputStream gz = new GZIPOutputStream(fos)) {
            gz.write(tarHeader("conflict/", '5', 0755, 0));
            gz.write(tarHeader("conflict/inner.txt", '0', 0644, 6));
            gz.write("inside".getBytes(StandardCharsets.UTF_8));
            gz.write(new byte[512 - 6]);
            gz.write(tarHeader("conflict", '0', 0644, 9));
            gz.write("file-wins".getBytes(StandardCharsets.UTF_8));
            gz.write(new byte[512 - 9]);
            gz.write(new byte[1024]);
        }
        Path dest = tmp.resolve("cout");
        Archives.extract(tgz, dest);
        assertTrue(Files.isRegularFile(dest.resolve("conflict")));
        assertEquals("file-wins", Files.readString(dest.resolve("conflict")));
    }

    // --- minimal ustar header writer for fixtures ------------------------------------

    private static byte[] tarHeader(String name, char type, int mode, long size) {
        byte[] h = new byte[512];
        writeStr(h, 0, name, 100);
        writeStr(h, 100, String.format("%07o", mode), 8);
        writeStr(h, 108, "0000000", 8);
        writeStr(h, 116, "0000000", 8);
        writeStr(h, 124, String.format("%011o", size), 12);
        writeStr(h, 136, "00000000000", 12);
        Arrays.fill(h, 148, 156, (byte) ' ');
        h[156] = (byte) type;
        writeStr(h, 257, "ustar\0", 6);
        writeStr(h, 263, "00", 2);
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xFF;
        }
        writeStr(h, 148, String.format("%06o\0 ", sum), 8);
        return h;
    }

    private static void writeStr(byte[] h, int off, String s, int len) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, h, off, Math.min(b.length, len));
    }
}
