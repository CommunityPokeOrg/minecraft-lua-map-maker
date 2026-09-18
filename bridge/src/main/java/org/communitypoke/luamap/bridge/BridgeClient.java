package org.communitypoke.luamap.bridge;

import org.communitypoke.luamap.bridge.BridgeProtocol.Request;
import org.communitypoke.luamap.bridge.BridgeProtocol.Response;

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
 * Minimal synchronous LuaBridge client — one socket per client instance,
 * one request in flight at a time. Used by tests and external tools.
 */
public final class BridgeClient implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private final AtomicLong ids = new AtomicLong();

    public BridgeClient(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    /** Send a request and wait for its response line. */
    public synchronized Response request(String op, String code, String name) throws IOException {
        Request r = new Request(ids.incrementAndGet(), op, code, name);
        out.println(BridgeProtocol.encode(r));
        String line = in.readLine();
        if (line == null) {
            throw new IOException("bridge closed connection");
        }
        return BridgeProtocol.decodeResponse(line);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
