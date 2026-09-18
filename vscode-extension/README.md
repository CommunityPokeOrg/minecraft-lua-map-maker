# LuaMap Tools — VS Code extension

Live [LuaBridge](../docs/luabridge.md) client for the Lua Map Maker mod. Connects
over the loopback NDJSON TCP protocol (protocol v1) to a running game started
with `--bridgePort` (default `25575`).

## Features

- **Status bar badge** — `Connected` / `Connecting` / `Error` / `Disconnected`,
  one click to connect.
- **LuaMap sidebar** (activity bar) — Session state + actions, **NPCs**
  inspector (name + `x, y, z`, refreshed every poll), and **Scripts** list
  (click to run).
- **Commands** — `LuaMap: Connect`, `Disconnect`, `Reconnect`, `Run Script`,
  `Evaluate Lua`, `Query Block`, `Refresh NPCs`.
- **Eval console** — script output, `=> result` values, and errors are logged
  to the **LuaMap** output channel.
- **NPC inspector** — `eval`s a `npc.list()` snippet, so it works against the
  stock protocol with no mod changes.
- **Block query** — `world.getblock(x, y, z)` via an input prompt.
- **Lua autocomplete + snippets** for `world.*`, `npc.*`, `player.*`, `chat`,
  `log`, `print` in `.lua` files.
- **Auto-reconnect** (~1.5 s) and configurable polling
  (`luamap.pollIntervalSeconds`, default 2 s). All socket I/O is
  event-driven — nothing blocks the UI.

## Usage

1. Start the game: `java -jar luamap-launcher.jar --bridgePort 25575`
   (or `--server --bridgePort 25575` headless).
2. Install the `.vsix`: `code --install-extension luamap-vscode-0.4.0.vsix`
   or Extensions view → `…` → *Install from VSIX…*.
3. `Ctrl+Shift+P` → **LuaMap: Connect** (defaults `127.0.0.1:25575`), or click
   the `LuaMap` status bar item.

## Settings

| Setting | Default | Notes |
|---|---|---|
| `luamap.bridge.host` | `127.0.0.1` | Bridge is loopback-only by design |
| `luamap.bridge.port` | `25575` | Match `--bridgePort` |
| `luamap.autoReconnect` | `true` | Reconnect ~1.5 s after a drop |
| `luamap.pollIntervalSeconds` | `2` | Status + NPC refresh cadence |

## Develop

```sh
cd vscode-extension
npm install
npm run compile   # tsc → out/
npm test          # compile + node:test suite (14 tests)
npm run package   # vsce → luamap-vscode-<ver>.vsix
```

Tests are plain `node:test` suites over a mock loopback NDJSON server —
no VS Code instance required. They cover NDJSON framing, request/response
id matching, connect/drop/reconnect, and status/NPC reply parsing.

## Limitations

- NPC state is identity + position only — the mod exposes no richer state.
- No breakpoint debugging yet; the bridge protocol has `eval|run|reload|list|
  status` only.
- The extension was smoke-verified by unit tests; full UI behavior inside VS
  Code was not exercised on a headless CI machine.
