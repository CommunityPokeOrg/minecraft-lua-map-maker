package org.communitypoke.luamap.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Tiny download helper on java.net.http. */
final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private Http() {
    }

    static String getString(String url) throws IOException, InterruptedException {
        HttpResponse<String> res = CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofMinutes(2)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("GET " + url + " -> HTTP " + res.statusCode());
        }
        return res.body();
    }

    /** Downloads {@code url} to {@code dest}; skips if the file already exists. */
    static void download(String url, Path dest) throws IOException, InterruptedException {
        if (Files.isRegularFile(dest) && Files.size(dest) > 0) {
            return;
        }
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        HttpResponse<InputStream> res = CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofMinutes(10)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new IOException("GET " + url + " -> HTTP " + res.statusCode());
        }
        try (InputStream in = res.body()) {
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
