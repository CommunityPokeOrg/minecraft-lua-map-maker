package org.communitypoke.luamap.bridge;

import org.communitypoke.luamap.bridge.BridgeProtocol.Request;
import org.communitypoke.luamap.bridge.BridgeProtocol.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Localhost-only TCP server speaking the LuaBridge protocol
 * (newline-delimited JSON — see {@link BridgeProtocol}).
 *
 * <p>One daemon thread accepts connections; each session is handled on a
 * cached daemon pool. Sessions may send any number of pipelined requests;
 * each line gets exactly one response line.
 */
public final class BridgeServer implements AutoCloseable {

    private final BridgeExecutor executor;
    private ServerSocket socket;
    private Thread acceptThread;
    private final ExecutorService sessions = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "luabridge-session");
        t.setDaemon(true);
        return t;
    });

    public BridgeServer(BridgeExecutor executor) {
        this.executor = executor;
    }

    /** Bind to 127.0.0.1:{@code port} and start accepting. {@code port} 0 = ephemeral. */
    public synchronized void start(int port) throws IOException {
        if (socket != null) {
            return;
        }
        socket = new ServerSocket();
        socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        acceptThread = new Thread(this::acceptLoop, "luabridge-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public boolean isRunning() {
        return socket != null && socket.isBound() && !socket.isClosed();
    }

    /** Actual bound port (useful when started with port 0). */
    public int port() {
        return socket == null ? -1 : socket.getLocalPort();
    }

    @Override
    public synchronized void close() {
        sessions.shutdownNow();
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }
    }

    private void acceptLoop() {
        while (isRunning()) {
            final Socket s;
            try {
                s = socket.accept();
            } catch (IOException e) {
                return; // closed
            }
            sessions.execute(() -> serve(s));
        }
    }

    private void serve(Socket s) {
        try (s;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(
                     s.getOutputStream(), StandardCharsets.UTF_8), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                Response r;
                try {
                    r = executor.handle(BridgeProtocol.decodeRequest(line));
                } catch (IllegalArgumentException e) {
                    r = Response.error(0, e.getMessage());
                } catch (Throwable t) {
                    r = Response.error(0, "internal: " + t.getMessage());
                }
                out.println(BridgeProtocol.encode(r));
            }
        } catch (IOException ignored) {
            // client hung up
        }
    }
}
