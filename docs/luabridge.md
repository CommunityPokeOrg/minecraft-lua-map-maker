# LuaBridge — local companion/debug bridge

LuaBridge is a tiny localhost socket server embedded in the mod. It exists so
external tools — first and foremost the `ide-plugin/` IntelliJ plugin — can
eval, run, and lint Lua map scripts against a live singleplayer world, which
is the closest practical "hot reload" under the mod's synchronous script model.

## Transport

- TCP, bound to `127.0.0.1` only.
- Newline-delimited JSON, UTF-8 — one request per line, one response per line,
  pipelining allowed (responses come in request order per session).
- Any number of concurrent sessions; requests are dispatched onto the
  Minecraft server thread, so scripts keep the same synchronous, safe
  execution model as `/luamap run`.
- The payload shape is transport-agnostic — the same JSON objects can later be
  carried over WebSocket or stdin/stdout IPC without changes.

## Enabling

Disabled by default. Enable with either:

```bash
java -jar luamap-launcher.jar --bridgePort 25575     # launcher flag
# or a file: <gameDir>/luamap-bridge.properties containing port=25575
```

Default port convention: `25575` (configurable; any free port works).

## Protocol v1

Request:

```json
{"v":1, "id":1, "op":"eval|run|reload|list|status", "code":"<lua>", "name":"<script>"}
```

Response:

```json
{"v":1, "id":1, "ok":true,  "result":"<lua return value>", "output":"<chat/log lines>"}
{"v":1, "id":1, "ok":false, "error":"<message>"}
```

| op       | fields      | behavior                                                  |
|----------|-------------|-----------------------------------------------------------|
| `eval`   | `code`      | run code in the sandbox; returns result + captured output |
| `run`    | `name`      | run `luamaps/<name>.lua`                                  |
| `reload` | `name`      | re-read from disk + syntax-check, **without executing**   |
| `list`   | —           | newline-separated script names                            |
| `status` | —           | `ok; scripts=N; npcs=M; world=...`                        |

## Security model

Loopback-only bind + the same sandbox as `/luamap eval` — a local process can
do exactly what an op running a script could. No auth (loopback); don't
expose the port and don't enable it on a shared multiplayer host.

## Roadmap / TODO

- `debug` session ops (breakpoints, step) — needs a frame model in LuaRuntime.
- Block-preview query op (`probe` returning a region dump) for the tool window.
- Line-number-mapped error squiggles in the IDE (LuaError already carries line info).
