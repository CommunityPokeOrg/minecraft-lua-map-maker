package org.communitypoke.luamap.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * LuaBridge wire protocol — version 1.
 *
 * <p>Newline-delimited JSON over a localhost TCP socket. One JSON object per
 * line, UTF-8, one request -> exactly one response, requests may be pipelined.
 * Deliberately transport-agnostic fields so a WebSocket framing can carry the
 * same payloads later without changing semantics.
 *
 * <pre>
 *   request:  {"v":1, "id":1, "op":"eval"|"run"|"reload"|"list"|"status",
 *              "code":"<lua>", "name":"<script>"}
 *   response: {"v":1, "id":1, "ok":true,  "result":"<lua return>",
 *              "output":"<captured chat/log lines>"}
 *             {"v":1, "id":1, "ok":false, "error":"<message>"}
 * </pre>
 *
 * <ul>
 *   <li>{@code eval}   — run {@code code} in the sandbox, return result+output</li>
 *   <li>{@code run}    — run the {@code name} script from {@code luamaps/}</li>
 *   <li>{@code reload} — re-read {@code name} from disk (scripts are read per
 *       run anyway; reload exists so tools can force a refresh + lint check
 *       without executing)</li>
 *   <li>{@code list}   — newline-separated script names</li>
 *   <li>{@code status} — "ok; scripts=N" style health line</li>
 * </ul>
 */
public final class BridgeProtocol {

    public static final int VERSION = 1;
    public static final int DEFAULT_PORT = 25575;

    public static final String OP_EVAL = "eval";
    public static final String OP_RUN = "run";
    public static final String OP_RELOAD = "reload";
    public static final String OP_LIST = "list";
    public static final String OP_STATUS = "status";

    private static final Gson GSON = new Gson();

    public record Request(long id, String op, String code, String name) {
    }

    public record Response(long id, boolean ok, String result, String output, String error) {

        public static Response ok(long id, String result, String output) {
            return new Response(id, true, result, output, null);
        }

        public static Response error(long id, String error) {
            return new Response(id, false, null, null, error);
        }
    }

    /** Serialize a request to one line (no trailing newline). */
    public static String encode(Request r) {
        JsonObject o = new JsonObject();
        o.addProperty("v", VERSION);
        o.addProperty("id", r.id());
        o.addProperty("op", r.op());
        if (r.code() != null) {
            o.addProperty("code", r.code());
        }
        if (r.name() != null) {
            o.addProperty("name", r.name());
        }
        return GSON.toJson(o);
    }

    /** Serialize a response to one line (no trailing newline). */
    public static String encode(Response r) {
        JsonObject o = new JsonObject();
        o.addProperty("v", VERSION);
        o.addProperty("id", r.id());
        o.addProperty("ok", r.ok());
        if (r.result() != null) {
            o.addProperty("result", r.result());
        }
        if (r.output() != null) {
            o.addProperty("output", r.output());
        }
        if (r.error() != null) {
            o.addProperty("error", r.error());
        }
        return GSON.toJson(o);
    }

    /**
     * Parse one request line.
     *
     * @throws IllegalArgumentException on malformed JSON or missing fields
     */
    public static Request decodeRequest(String line) {
        JsonObject o = parse(line);
        long id = o.has("id") ? o.get("id").getAsLong() : 0;
        if (!o.has("op") || !o.get("op").isJsonPrimitive()) {
            throw new IllegalArgumentException("request missing 'op'");
        }
        return new Request(id,
                o.get("op").getAsString(),
                o.has("code") ? o.get("code").getAsString() : null,
                o.has("name") ? o.get("name").getAsString() : null);
    }

    /** Parse one response line. */
    public static Response decodeResponse(String line) {
        JsonObject o = parse(line);
        long id = o.has("id") ? o.get("id").getAsLong() : 0;
        boolean ok = o.has("ok") && o.get("ok").getAsBoolean();
        return new Response(id, ok,
                o.has("result") ? o.get("result").getAsString() : null,
                o.has("output") ? o.get("output").getAsString() : null,
                o.has("error") ? o.get("error").getAsString() : null);
    }

    private static JsonObject parse(String line) {
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (o.has("v") && o.get("v").getAsInt() != VERSION) {
                throw new IllegalArgumentException("unsupported protocol version " + o.get("v"));
            }
            return o;
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new IllegalArgumentException("malformed request: " + e.getMessage());
        }
    }

    private BridgeProtocol() {
    }
}
