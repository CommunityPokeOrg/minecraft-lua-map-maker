package org.communitypoke.luamap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptLibraryTest {

    @TempDir
    Path dir;

    @Test
    void normalizeNameAppendsExtension() {
        assertEquals("arena.lua", ScriptLibrary.normalizeName("arena"));
        assertEquals("arena.lua", ScriptLibrary.normalizeName("arena.lua"));
        assertEquals("my-map_2.lua", ScriptLibrary.normalizeName("my-map_2"));
    }

    @Test
    void normalizeNameRejectsTraversalAndBadChars() {
        assertThrows(IllegalArgumentException.class, () -> ScriptLibrary.normalizeName("../evil"));
        assertThrows(IllegalArgumentException.class, () -> ScriptLibrary.normalizeName("a/b"));
        assertThrows(IllegalArgumentException.class, () -> ScriptLibrary.normalizeName(""));
        assertThrows(IllegalArgumentException.class, () -> ScriptLibrary.normalizeName("x.lua.txt"));
        assertThrows(IllegalArgumentException.class, () -> ScriptLibrary.normalizeName(null));
    }

    @Test
    void listReturnsSortedNamesWithoutExtension() throws Exception {
        Files.writeString(dir.resolve("b.lua"), "-- b");
        Files.writeString(dir.resolve("a.lua"), "-- a");
        Files.writeString(dir.resolve("not-lua.txt"), "nope");
        assertEquals(java.util.List.of("a", "b"), new ScriptLibrary(dir).list());
    }

    @Test
    void listEmptyWhenDirectoryMissing() {
        assertTrue(new ScriptLibrary(dir.resolve("nope")).list().isEmpty());
    }

    @Test
    void readRoundTripsAndRejectsMissing() throws Exception {
        ScriptLibrary lib = new ScriptLibrary(dir);
        Files.writeString(dir.resolve("ok.lua"), "chat('hi')");
        assertEquals("chat('hi')", lib.read("ok"));
        assertThrows(java.io.IOException.class, () -> lib.read("missing"));
    }

    @Test
    void seedBundledExamplesCopiesFromClasspath() throws Exception {
        ScriptLibrary lib = new ScriptLibrary(dir.resolve("luamaps"));
        int seeded = lib.seedBundledExamples();
        // At least one bundled example ships in the mod jar/test resources.
        assertTrue(seeded >= 1, "expected bundled examples on the classpath");
        assertTrue(Files.exists(dir.resolve("luamaps").resolve("hello.lua")));
        // Second seeding must not overwrite user edits.
        Files.writeString(dir.resolve("luamaps").resolve("hello.lua"), "-- edited");
        lib.seedBundledExamples();
        assertEquals("-- edited", Files.readString(dir.resolve("luamaps").resolve("hello.lua")));
        assertFalse(lib.list().isEmpty());
    }
}
