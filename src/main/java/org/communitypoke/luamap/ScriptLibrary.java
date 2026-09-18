package org.communitypoke.luamap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Manages the {@code luamaps/} directory: script discovery, safe name
 * resolution, and seeding the bundled example scripts shipped inside the
 * mod jar under {@code luamaps/}.
 */
public final class ScriptLibrary {
    /** Classpath location inside the mod jar listing bundled scripts, one per line. */
    public static final String INDEX_RESOURCE = "luamaps/index.txt";
    public static final String RESOURCE_PREFIX = "luamaps/";

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]+(\\.lua)?");

    private final Path directory;

    public ScriptLibrary(Path directory) {
        this.directory = directory;
    }

    public Path directory() {
        return directory;
    }

    /** Validate a user-supplied script name; returns the canonical file name (with .lua). */
    public static String normalizeName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid script name '" + name + "' (allowed: letters, digits, - and _)");
        }
        return name.endsWith(".lua") ? name : name + ".lua";
    }

    /** Sorted list of script names (without .lua extension). */
    public List<String> list() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".lua"))
                  .forEach(p -> {
                      String n = p.getFileName().toString();
                      names.add(n.substring(0, n.length() - ".lua".length()));
                  });
        } catch (IOException ignored) {
            return List.of();
        }
        Collections.sort(names);
        return names;
    }

    public Path resolve(String name) {
        return directory.resolve(normalizeName(name));
    }

    public boolean exists(String name) {
        try {
            return Files.isRegularFile(resolve(name));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String read(String name) throws IOException {
        Path p = resolve(name);
        if (!Files.isRegularFile(p)) {
            throw new IOException("No such script: " + name);
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * Copy every script listed in {@code luamaps/index.txt} from the jar into
     * the scripts directory, skipping files that already exist (so user edits
     * are never overwritten).
     *
     * @return number of scripts written
     */
    public int seedBundledExamples() throws IOException {
        ClassLoader cl = ScriptLibrary.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(INDEX_RESOURCE)) {
            if (in == null) {
                return 0;
            }
            String index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Files.createDirectories(directory);
            int written = 0;
            for (String line : index.split("\\R")) {
                String file = line.trim();
                if (file.isEmpty() || file.startsWith("#")) {
                    continue;
                }
                Path target = directory.resolve(file);
                if (Files.exists(target)) {
                    continue;
                }
                try (InputStream script = cl.getResourceAsStream(RESOURCE_PREFIX + file)) {
                    if (script == null) {
                        continue;
                    }
                    Files.write(target, script.readAllBytes());
                    written++;
                }
            }
            return written;
        }
    }
}
