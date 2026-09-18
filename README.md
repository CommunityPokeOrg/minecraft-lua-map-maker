# Lua Map Maker

A Minecraft **Fabric mod** that lets map makers build maps in **Lua**, plus a
**single-file launcher JAR** that downloads and starts the whole game for
quick testing — no Minecraft install, no launcher, no account required.

Drop `.lua` scripts into the `luamaps/` folder, run `/luamap run <name>`
in-game, and the script builds in the world. That's it.

Built for Wolfy ([@wolfybl](https://github.com/wolfybl)) to quick-test map
scripts.

## Quick start (for Wolfy)

You need **Java 17 or newer** on your PATH. Then:

```bash
java -jar luamap-launcher.jar
```

First run downloads ~500 MB (vanilla client, libraries, assets, Fabric
loader) into `./luamap-run/` — subsequent runs start instantly.

In the game:

1. `Singleplayer → Create New World` (creative + flat recommended)
2. `/luamap list` — shows the bundled example scripts
3. `/luamap run arena` — builds a walled arena around you
4. `/luamap run tower` — builds a lookout tower
5. `/luamap eval chat('hi from lua')` — evaluate a one-liner

Your own scripts go in `luamap-run/luamaps/`. The file name (minus `.lua`) is
the command name. Scripts that build "around you" should use
`player.pos()` as their origin — see `arena.lua` for the pattern.

### Headless mode

On a machine without a display, the same jar can run a dedicated server
(flat creative world, offline mode) — `/luamap` commands work from the
server console:

```bash
java -jar luamap-launcher.jar --server
```

Then type e.g. `luamap run arena` at the server console.

### Launcher options

```
java -jar luamap-launcher.jar [options]

  --server          dedicated server instead of the client
  --gameDir DIR     run directory (default: ./luamap-run)
  --username NAME   offline-mode username (default: Wolfy)
  --xmx SIZE        game heap (default: 2G)
  --mc VERSION      override the bundled Minecraft version
```

## Lua API

Scripts run in a sandboxed Lua 5.2 environment (LuaJ). The API is a set of
global tables:

### `world` — block editing and world state

```lua
world.setblock(x, y, z, "block")                    -- one block
world.fill(x1,y1,z1, x2,y2,z2, "block")             -- cuboid, returns count
world.hollow(x1,y1,z1, x2,y2,z2, "block")           -- cuboid shell (walls)
world.getblock(x, y, z)                             -- -> "minecraft:stone"
world.spawn(x, y, z)                                -- set world spawn
world.time("day")                                   -- or "noon"|"night"|"midnight"|ticks
world.weather("clear")                              -- or "rain"|"thunder"
```

Block names accept a namespace (`"minecraft:stone"`), omit it for vanilla
(`"stone"`), and take block states in brackets
(`"minecraft:chest[facing=north]"`, `"minecraft:ladder[facing=south]"`).

`fill`/`hollow` are capped at **1,000,000 blocks per call** — split bigger
regions into several calls.

### `player` — the player who ran the command

```lua
if player.exists() then           -- false when run from server console
  local x, y, z = player.pos()
  player.teleport(x, y + 10, z)
  player.give("minecraft:diamond_sword", 1)
  chat("hi " .. player.name())
end
```

### Output

```lua
chat("message")   -- sends to the player / server console
log("message")    -- same thing (alias)
print(...)        -- also routed to chat
```

Return a value from a script (`return expr`) to have it echoed back as
`= <value>`.

### Example

```lua
-- luamaps/my_map.lua
local cx, cy, cz = 0, 64, 0
if player.exists() then
  cx, cy, cz = player.pos()
  cx, cy, cz = math.floor(cx), math.floor(cy), math.floor(cz)
end

world.fill(cx-10, cy-1, cz-10, cx+10, cy-1, cz+10, "minecraft:grass_block")
world.hollow(cx-10, cy, cz-10, cx+10, cy+4, cz+10, "minecraft:stone_bricks")
world.spawn(cx, cy, cz)
world.time("day"); world.weather("clear")
chat("map ready!")
```

More in [`src/main/resources/luamaps/`](src/main/resources/luamaps/) — these
are auto-copied into `<gameDir>/luamaps/` on first launch.

## Architecture

```
┌──────────────────────────── repo ────────────────────────────┐
│                                                              │
│  root project  = the Fabric mod (Fabric Loom / Gradle)       │
│  launcher/     = standalone Java launcher (fat jar)          │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### Loader choice: Fabric

- **Fastest path to a working dev/test loop** — Fabric Loom handles
  deobfuscation, remapping (yarn), and dev runtime with almost no config.
- **Loader JAR is embeddable** — the mod jar carries LuaJ inside itself
  (Jar-in-Jar via `include`), so users need no extra dependencies.
- Lightweight, minimal overhead; easy port to NeoForge later if needed.

### Lua engine: LuaJ (`org.luaj:luaj-jse`)

- Pure-Java Lua 5.2 — no native libraries to ship or crash.
- Mature, widely used for embedded Minecraft scripting.
- Sandboxed in [`LuaRuntime`](src/main/java/org/communitypoke/luamap/lua/LuaRuntime.java):
  `io`, `luajava`, `package`/`require`, `dofile`/`loadfile`, and the dangerous
  `os.*` functions are removed — scripts can only touch the game through the
  `world`/`player` tables.

### Mod internals

| File | Role |
|---|---|
| `LuaMapMakerMod` | entrypoint; seeds bundled examples into `luamaps/` |
| `command/LuaMapCommand` | `/luamap list\|dir\|run\|eval` (Brigadier) |
| `ScriptLibrary` | script discovery, name validation, example seeding |
| `lua/LuaRuntime` | sandboxed LuaJ globals + `run()` |
| `lua/LuaContext` | world + invoking player + output sink |
| `lua/api/WorldApi` | `world.*` — setblock/fill/hollow/getblock/spawn/time/weather |
| `lua/api/PlayerApi` | `player.*` — exists/name/pos/teleport/give |
| `lua/BlockStates` | `"name[props]"` → `BlockState` parser |

Commands require permission level 2 (singleplayer cheats / server op).

### Launcher internals (`launcher/`)

A ~700-line Java program, no external framework beyond gson (merged into the
jar):

1. Fetches the vanilla **version manifest** and version JSON from Mojang.
2. Downloads **libraries + natives + client jar + assets** (assets in a
   parallel pool, resumable — existing files are skipped).
3. Fetches the **Fabric loader profile** from `meta.fabricmc.net` for the
   pinned loader version.
4. Extracts the **bundled mod jar** (`bundled-mods/` inside the launcher jar)
   and downloads **Fabric API** into `mods/`.
5. Builds the classpath and launch arguments (offline `legacy` auth, random
   UUID) and starts `net.fabricmc.loader.impl.launch.knot.KnotClient`.

`--server` instead downloads the official Fabric server launcher jar,
writes `eula.txt` + a flat-creative `server.properties`, installs the same
mods, and runs it.

## Repository layout

```
├── build.gradle            # mod build (Fabric Loom)
├── settings.gradle         # includes :launcher
├── gradle.properties       # pinned versions (MC, loader, Fabric API, LuaJ)
├── gradlew / gradlew.bat   # Gradle wrapper (8.10)
├── src/main/java/...       # mod source
├── src/main/resources/
│   ├── fabric.mod.json
│   └── luamaps/            # bundled example scripts (index.txt lists them)
├── src/test/java/...       # JUnit tests (sandbox, script library, parser)
├── launcher/
│   ├── build.gradle        # fatJar → luamap-launcher.jar
│   └── src/main/java/...   # downloader / installer / bootstrap
└── README.md
```

## Building from source

Requires **JDK 17** and internet access on first build.

```bash
./gradlew build
```

Outputs:

- `build/libs/luamap-0.1.0.jar` — the mod jar (LuaJ embedded)
- `launcher/build/libs/luamap-launcher.jar` — the runnable launcher
  (contains the mod jar)

Tests: `./gradlew test` — JUnit suite covering the Lua sandbox, script
library, and block-spec parsing.

Run a dev client straight from Gradle (uses a real Minecraft account or
offline, depending on Loom config):

```bash
./gradlew runClient
```

## Limitations

- **Minecraft 1.20.4 + Java 17+ only** for now (pinned in
  `gradle.properties`; bump `minecraft_version`/mappings to port).
- **Scripts run synchronously** on the server thread — an infinite loop
  (`while true do end`) will hang the game. Keep scripts finite.
- **Absolute coordinates** — `world.*` functions use world coordinates, not
  relative `~` notation. Use `player.pos()` as an anchor.
- **Offline mode** — the launcher never logs into a Mojang account;
  usernames are whatever `--username` says, and multiplayer skin/session
  features don't apply. No Mojang terms are bypassed — offline mode is a
  vanilla feature; assets and game files are downloaded from Mojang's
  official servers.
- **1M block cap** per `fill`/`hollow` call.
- No undo/redo, no schematic import/export, no per-map packaging — yet.
- Lua is 5.2 semantics (LuaJ), not 5.3+/LuaJIT.

## License

MIT — see [LICENSE](LICENSE).
