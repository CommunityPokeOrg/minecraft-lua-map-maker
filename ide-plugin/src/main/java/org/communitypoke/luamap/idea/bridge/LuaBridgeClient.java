package org.communitypoke.luamap.idea.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synchronous client for the LuaBridge newline-delimited-JSON protocol
 * (see docs/luabridge.md in the repo root). IntelliJ bundles Gson on the
 * platform classpath, so no extra dependency is needed.
 */
public final class LuaBridgeClient implements AutoCloseable {

    public static final int DEFAULT_PORT = 25575;

    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private final AtomicLong ids = new AtomicLong();

    public LuaBridgeClient(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(60_000);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    public record Reply(boolean ok, String result, String output, String error) {
    }

    public synchronized Reply call(String op, String code, String name) throws IOException {
        JsonObject req = new JsonObject();
        long id = ids.incrementAndGet();
        req.addProperty("v", 1);
        req.addProperty("id", id);
        req.addProperty("op", op);
        if (code != null) {
            req.addProperty("code", code);
        }
        if (name != null) {
            req.addProperty("name", name);
        }
        out.println(req);
        String line = in.readLine();
        if (line == null) {
            throw new IOException("bridge closed the connection");
        }
        JsonObject r = JsonParser.parseString(line).getAsJsonObject();
        return new Reply(
                r.has("ok") && r.get("ok").getAsBoolean(),
                r.has("result") ? r.get("result").getAsString() : null,
                r.has("output") ? r.get("output").getAsString() : null,
                r.has("error") ? r.get("error").getAsString() : null);
    }

    public Reply eval(String luaCode) throws IOException {
        return call("eval", luaCode, null);
    }

    public Reply run(String scriptName) throws IOException {
        return call("run", null, scriptName);
    }

    public Reply status() throws IOException {
        return call("status", null, null);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
