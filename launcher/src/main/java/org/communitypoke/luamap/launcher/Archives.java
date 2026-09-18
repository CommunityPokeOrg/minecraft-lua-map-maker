package org.communitypoke.luamap.launcher;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Minimal archive extraction for JRE payloads — sniffs the format (ZIP vs
 * tar.gz), guards against path traversal, preserves tar file modes and
 * symlinks. No third-party dependency, keeps the launcher jar small.
 */
final class Archives {

    private Archives() {
    }

    /** Extracts {@code archive} into {@code dest} — ZIP or gzip'd tar, detected by magic bytes. */
    static void extract(Path archive, Path dest) throws IOException {
        Files.deleteIfExists(dest);
        Files.createDirectories(dest);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(archive))) {
            in.mark(2);
            int b0 = in.read();
            int b1 = in.read();
            in.reset();
            if (b0 == 0x1F && b1 == 0x8B) {
                untar(in, dest); // gzip-wrapped tar
            } else if (b0 == 'P' && b1 == 'K') {
                unzip(archive, dest);
            } else {
                throw new IOException("Unrecognized archive format: " + archive);
            }
        }
    }

    private static void unzip(Path archive, Path dest) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                Path out = safeResolve(dest, e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * POSIX tar reader: handles regular files, directories, symlinks,
     * hardlinks, GNU longname/longlink ('L'/'K') and skips pax headers.
     */
    private static void untar(InputStream raw, Path dest) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(raw, 8192)) {
            byte[] header = new byte[512];
            String longName = null;
            String longLink = null;
            while (readFully(in, header)) {
                if (isZeroBlock(header)) {
                    break;
                }
                String name = longName != null ? longName
                        : field(header, 0, 100) + ustarPrefix(header);
                String linkName = longLink != null ? longLink : field(header, 157, 100);
                long size = octal(header, 124, 12);
                long mode = octal(header, 100, 8);
                char type = (char) header[156];
                longName = null;
                longLink = null;

                switch (type) {
                    case 'L' -> longName = readString(in, size);
                    case 'K' -> longLink = readString(in, size);
                    case 'x', 'g' -> skipBody(in, size); // pax extended headers
                    case '5' -> Files.createDirectories(safeResolve(dest, name));
                    case '2' -> {
                        Path out = safeResolve(dest, name);
                        Files.createDirectories(out.getParent());
                        Files.deleteIfExists(out);
                        Files.createSymbolicLink(out, Path.of(linkName));
                    }
                    case '1' -> {
                        Path out = safeResolve(dest, name);
                        Files.createDirectories(out.getParent());
                        Files.deleteIfExists(out);
                        Files.createLink(out, safeResolve(dest, linkName));
                    }
                    case '0', '\0', '7' -> {
                        Path out = safeResolve(dest, name);
                        Files.createDirectories(out.getParent());
                        copyBody(in, out, size);
                        applyMode(out, mode);
                    }
                    default -> skipBody(in, size); // char/block devs, fifos — not needed
                }
            }
        }
    }

    private static String ustarPrefix(byte[] header) {
        // ustar: prefix field 345-500 prepended to name when magic matches
        String magic = field(header, 257, 6);
        if (!magic.startsWith("ustar")) {
            return "";
        }
        String prefix = field(header, 345, 155);
        return prefix.isEmpty() ? "" : prefix + "/";
    }

    private static String field(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) {
            end++;
        }
        return new String(h, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] h, int off, int len) {
        long value = 0;
        for (int i = off; i < off + len; i++) {
            int c = h[i] & 0xFF;
            if (c == ' ' || c == 0) {
                continue;
            }
            if (c < '0' || c > '7') {
                break;
            }
            value = (value << 3) | (c - '0');
        }
        return value;
    }

    private static boolean isZeroBlock(byte[] h) {
        for (byte b : h) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return off > 0;
            }
            off += n;
        }
        return true;
    }

    private static String readString(InputStream in, long size) throws IOException {
        byte[] data = new byte[(int) size];
        readFully(in, data);
        skipPadding(in, size);
        int end = data.length;
        while (end > 0 && data[end - 1] == 0) {
            end--;
        }
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    private static void copyBody(InputStream in, Path out, long size) throws IOException {
        try (OutputStream os = Files.newOutputStream(out)) {
            byte[] buf = new byte[8192];
            long remaining = size;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    throw new IOException("Truncated tar entry: " + out);
                }
                os.write(buf, 0, n);
                remaining -= n;
            }
        }
        skipPadding(in, size);
    }

    private static void skipBody(InputStream in, long size) throws IOException {
        long remaining = size + pad(size);
        while (remaining > 0) {
            long n = in.skip(remaining);
            if (n <= 0) {
                if (in.read() < 0) {
                    return;
                }
                n = 1;
            }
            remaining -= n;
        }
    }

    private static long pad(long size) {
        return (512 - (size % 512)) % 512;
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long p = pad(size);
        for (long i = 0; i < p; i++) {
            if (in.read() < 0) {
                return;
            }
        }
    }

    private static void applyMode(Path file, long mode) {
        try {
            StringBuilder perms = new StringBuilder(9);
            long m = mode & 0x1FF;
            String bits = "rwx";
            for (int j = 0; j < 9; j++) {
                perms.append(((m >> (8 - j)) & 1) == 1 ? bits.charAt(j % 3) : '-');
            }
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(perms.toString()));
        } catch (UnsupportedOperationException | SecurityException | IOException ignored) {
            // non-POSIX filesystem — modes don't apply
        }
    }

    private static Path safeResolve(Path dest, String name) throws IOException {
        Path out = dest.resolve(name).normalize();
        if (!out.startsWith(dest)) {
            throw new IOException("Archive entry escapes destination: " + name);
        }
        return out;
    }
}
