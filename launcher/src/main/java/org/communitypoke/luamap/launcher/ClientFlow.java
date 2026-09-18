package org.communitypoke.luamap.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Client path: fetch the vanilla version manifest, libraries, natives, and
 * assets; layer the Fabric loader profile on top; drop mods into
 * {@code mods/}; then launch {@code KnotClient}.
 */
final class ClientFlow {

    private static final String VERSION_MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FABRIC_META = "https://meta.fabricmc.net";
    private static final String RESOURCES = "https://resources.download.minecraft.net";
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    private ClientFlow() {
    }

    static int run(Main.Versions v, Path gameDir, String username, String xmx) throws Exception {
        Path abs = gameDir.toAbsolutePath();
        Path versionsDir = Files.createDirectories(abs.resolve("versions"));
        Path libDir = Files.createDirectories(abs.resolve("libraries"));
        Path assetsDir = Files.createDirectories(abs.resolve("assets"));
        Path nativesDir = Files.createDirectories(abs.resolve("natives"));

        // --- Vanilla version json ------------------------------------------------
        JsonObject manifest = JsonParser.parseString(Http.getString(VERSION_MANIFEST)).getAsJsonObject();
        String versionUrl = null;
        for (JsonElement el : manifest.getAsJsonArray("versions")) {
            JsonObject ver = el.getAsJsonObject();
            if (v.mc().equals(ver.get("id").getAsString())) {
                versionUrl = ver.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IllegalStateException("Minecraft version " + v.mc() + " not in version manifest");
        }
        String versionJsonText = Http.getString(versionUrl);
        Files.writeString(versionsDir.resolve(v.mc() + ".json"), versionJsonText);
        JsonObject version = JsonParser.parseString(versionJsonText).getAsJsonObject();

        // --- Libraries + natives ---------------------------------------------------
        List<Path> classpath = new ArrayList<>();
        for (JsonElement el : version.getAsJsonArray("libraries")) {
            JsonObject lib = el.getAsJsonObject();
            if (!Os.allowed(lib.get("rules"))) {
                continue;
            }
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads == null) {
                continue;
            }
            if (downloads.has("artifact")) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                Path dest = libDir.resolve(artifact.get("path").getAsString());
                Http.download(artifact.get("url").getAsString(), dest);
                classpath.add(dest);
            }
            if (lib.has("natives")) {
                JsonObject natives = lib.getAsJsonObject("natives");
                JsonElement classifierEl = natives.get(Os.name());
                if (classifierEl == null) {
                    continue;
                }
                String classifier = classifierEl.getAsString().replace("${arch}", archToken());
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                if (classifiers == null || !classifiers.has(classifier)) {
                    continue;
                }
                JsonObject n = classifiers.getAsJsonObject(classifier);
                Path nativesJar = libDir.resolve(n.get("path").getAsString());
                Http.download(n.get("url").getAsString(), nativesJar);
                unzip(nativesJar, nativesDir);
            }
        }

        // --- Client jar --------------------------------------------------------------
        JsonObject clientDl = version.getAsJsonObject("downloads").getAsJsonObject("client");
        Path clientJar = versionsDir.resolve(v.mc() + ".jar");
        Http.download(clientDl.get("url").getAsString(), clientJar);
        classpath.add(clientJar);

        // --- Assets --------------------------------------------------------------------
        JsonObject assetIndex = version.getAsJsonObject("assetIndex");
        String indexId = assetIndex.get("id").getAsString();
        Path indexPath = assetsDir.resolve("indexes").resolve(indexId + ".json");
        Http.download(assetIndex.get("url").getAsString(), indexPath);
        downloadAssets(assetsDir, indexPath);

        // --- Fabric loader profile -----------------------------------------------------
        String profileUrl = FABRIC_META + "/v2/versions/loader/" + v.mc() + "/" + v.loader()
                + "/profile/json";
        JsonObject profile = JsonParser.parseString(Http.getString(profileUrl)).getAsJsonObject();
        for (JsonElement el : profile.getAsJsonArray("libraries")) {
            JsonObject lib = el.getAsJsonObject();
            String coords = lib.get("name").getAsString();
            String base = lib.has("url") ? lib.get("url").getAsString() : MAVEN_CENTRAL;
            String rel = mavenPath(coords);
            Path dest = libDir.resolve(rel);
            Http.download(base + rel, dest);
            classpath.add(dest);
        }
        String mainClass = profile.get("mainClass").getAsString();

        // --- Mods ------------------------------------------------------------------------
        Mods.install(abs, v);

        // --- Assemble the launch command ---------------------------------------------------
        String cp = joinClasspath(classpath);
        Map<String, String> ph = new HashMap<>();
        ph.put("auth_player_name", username);
        ph.put("version_name", v.mc());
        ph.put("game_directory", abs.toString());
        ph.put("assets_root", assetsDir.toString());
        ph.put("assets_index_name", indexId);
        ph.put("auth_uuid", offlineUuid(username));
        ph.put("auth_access_token", "0");
        ph.put("clientid", "0");
        ph.put("auth_xuid", "0");
        ph.put("user_type", "legacy");
        ph.put("version_type", "fabric");
        ph.put("natives_directory", nativesDir.toString());
        ph.put("library_directory", libDir.toString());
        ph.put("launcher_name", "luamap-launcher");
        ph.put("launcher_version", v.mod());
        ph.put("classpath", cp);
        ph.put("classpath_separator", File.pathSeparator);

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin());
        cmd.add("-Xmx" + xmx);
        addArgs(profile.getAsJsonObject("arguments"), "jvm", cmd, ph);
        addArgs(version.getAsJsonObject("arguments"), "jvm", cmd, ph);
        cmd.add(mainClass);
        addArgs(version.getAsJsonObject("arguments"), "game", cmd, ph);
        addArgs(profile.getAsJsonObject("arguments"), "game", cmd, ph);

        System.out.println("Launching Minecraft " + v.mc() + " (Fabric " + v.loader() + ") as " + username);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(abs.toFile());
        pb.inheritIO();
        return pb.start().waitFor();
    }

    private static String archToken() {
        // Vanilla natives classifiers sometimes contain ${arch}; Mojang's launcher
        // substitutes a bitness token.
        return "arm64".equals(Os.arch()) ? "arm64" : "64";
    }

    private static String mavenPath(String coords) {
        // group:artifact:version[:classifier]
        String[] parts = coords.split(":");
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String ver = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + ver + "/" + artifact + "-" + ver + classifier + ".jar";
    }

    private static String joinClasspath(List<Path> jars) {
        StringBuilder sb = new StringBuilder();
        for (Path p : jars) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparator);
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static String offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String javaBin() {
        String exe = "windows".equals(Os.name()) ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    /**
     * Appends an "arguments" array to {@code cmd}. Elements are either plain
     * strings or {@code {rules, value}} objects (value is a string or array).
     */
    private static void addArgs(JsonObject arguments, String key, List<String> cmd,
                                Map<String, String> placeholders) {
        if (arguments == null || !arguments.has(key)) {
            return;
        }
        for (JsonElement el : arguments.getAsJsonArray(key)) {
            if (el.isJsonPrimitive()) {
                cmd.add(substitute(el.getAsString(), placeholders));
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            if (!Os.allowed(obj.get("rules"))) {
                continue;
            }
            JsonElement value = obj.get("value");
            if (value.isJsonArray()) {
                for (JsonElement v : value.getAsJsonArray()) {
                    cmd.add(substitute(v.getAsString(), placeholders));
                }
            } else {
                cmd.add(substitute(value.getAsString(), placeholders));
            }
        }
    }

    private static String substitute(String s, Map<String, String> placeholders) {
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            if (s.contains("${" + e.getKey() + "}")) {
                s = s.replace("${" + e.getKey() + "}", e.getValue());
            }
        }
        return s;
    }

    private static void unzip(Path jar, Path destDir) throws java.io.IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory() || e.getName().startsWith("META-INF")) {
                    continue;
                }
                Path out = destDir.resolve(e.getName());
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void downloadAssets(Path assetsDir, Path indexPath) throws Exception {
        JsonObject index = JsonParser.parseString(
                Files.readString(indexPath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject objects = index.getAsJsonObject("objects");
        if (objects == null) {
            return;
        }
        Path objectsDir = assetsDir.resolve("objects");
        List<String[]> jobs = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
            jobs.add(new String[]{hash});
        }
        // Skip files that already exist.
        jobs.removeIf(j -> Files.isRegularFile(objectsDir.resolve(j[0].substring(0, 2)).resolve(j[0])));
        if (jobs.isEmpty()) {
            return;
        }
        System.out.println("Downloading " + jobs.size() + " asset files…");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (String[] j : jobs) {
            String hash = j[0];
            pool.submit(() -> {
                Path dest = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
                String url = RESOURCES + "/" + hash.substring(0, 2) + "/" + hash;
                Exception last = null;
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        Http.download(url, dest);
                        last = null;
                        break;
                    } catch (Exception e) {
                        last = e;
                    }
                }
                if (last != null) {
                    failed.incrementAndGet();
                    System.err.println("  asset failed: " + hash + " (" + last.getMessage() + ")");
                    return;
                }
                int n = done.incrementAndGet();
                if (n % 500 == 0) {
                    System.out.println("  assets: " + n + "/" + jobs.size());
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.MINUTES);
        if (failed.get() > 0) {
            throw new IllegalStateException(failed.get() + " asset downloads failed");
        }
        System.out.println("Assets ready.");
    }
}
